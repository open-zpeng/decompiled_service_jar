package com.android.server.backup;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.IBackupAgent;
import android.app.PendingIntent;
import android.app.backup.IBackupManager;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.app.backup.IFullBackupRestoreObserver;
import android.app.backup.IRestoreSession;
import android.app.backup.ISelectBackupTransportCallback;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.PowerSaveState;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.storage.IStorageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.EventLog;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.backup.IBackupTransport;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.AppWidgetBackupBridge;
import com.android.server.BatteryService;
import com.android.server.EventLogTags;
import com.android.server.SystemConfig;
import com.android.server.SystemService;
import com.android.server.backup.DataChangedJournal;
import com.android.server.backup.fullbackup.FullBackupEntry;
import com.android.server.backup.fullbackup.PerformFullTransportBackupTask;
import com.android.server.backup.internal.BackupHandler;
import com.android.server.backup.internal.BackupRequest;
import com.android.server.backup.internal.ClearDataObserver;
import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.internal.Operation;
import com.android.server.backup.internal.PerformInitializeTask;
import com.android.server.backup.internal.ProvisionedObserver;
import com.android.server.backup.internal.RunBackupReceiver;
import com.android.server.backup.internal.RunInitializeReceiver;
import com.android.server.backup.params.AdbBackupParams;
import com.android.server.backup.params.AdbParams;
import com.android.server.backup.params.AdbRestoreParams;
import com.android.server.backup.params.BackupParams;
import com.android.server.backup.params.ClearParams;
import com.android.server.backup.params.ClearRetryParams;
import com.android.server.backup.params.RestoreParams;
import com.android.server.backup.restore.ActiveRestoreSession;
import com.android.server.backup.restore.PerformUnifiedRestoreTask;
import com.android.server.backup.transport.OnTransportRegisteredListener;
import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.transport.TransportNotRegisteredException;
import com.android.server.backup.utils.AppBackupUtils;
import com.android.server.backup.utils.BackupManagerMonitorUtils;
import com.android.server.backup.utils.BackupObserverUtils;
import com.android.server.backup.utils.SparseArrayUtils;
import com.android.server.job.JobSchedulerShellCommand;
import com.android.server.usage.AppStandbyController;
import com.google.android.collect.Sets;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
/* loaded from: classes.dex */
public class BackupManagerService implements BackupManagerServiceInterface {
    private static final String BACKUP_ENABLE_FILE = "backup_enabled";
    public static final String BACKUP_FILE_HEADER_MAGIC = "ANDROID BACKUP\n";
    public static final int BACKUP_FILE_VERSION = 5;
    public static final String BACKUP_FINISHED_ACTION = "android.intent.action.BACKUP_FINISHED";
    public static final String BACKUP_FINISHED_PACKAGE_EXTRA = "packageName";
    public static final String BACKUP_MANIFEST_FILENAME = "_manifest";
    public static final int BACKUP_MANIFEST_VERSION = 1;
    public static final String BACKUP_METADATA_FILENAME = "_meta";
    public static final int BACKUP_METADATA_VERSION = 1;
    public static final int BACKUP_WIDGET_METADATA_TOKEN = 33549569;
    private static final int BUSY_BACKOFF_FUZZ = 7200000;
    private static final long BUSY_BACKOFF_MIN_MILLIS = 3600000;
    private static final boolean COMPRESS_FULL_BACKUPS = true;
    private static final int CURRENT_ANCESTRAL_RECORD_VERSION = 1;
    public static final boolean DEBUG = true;
    public static final boolean DEBUG_BACKUP_TRACE = true;
    public static final boolean DEBUG_SCHEDULING = true;
    private static final long INITIALIZATION_DELAY_MILLIS = 3000;
    private static final String INIT_SENTINEL_FILE_NAME = "_need_init_";
    public static final String KEY_WIDGET_STATE = "￭￭widget";
    public static final boolean MORE_DEBUG = false;
    private static final int OP_ACKNOWLEDGED = 1;
    public static final int OP_PENDING = 0;
    private static final int OP_TIMEOUT = -1;
    public static final int OP_TYPE_BACKUP = 2;
    public static final int OP_TYPE_BACKUP_WAIT = 0;
    public static final int OP_TYPE_RESTORE_WAIT = 1;
    public static final String PACKAGE_MANAGER_SENTINEL = "@pm@";
    public static final String RUN_BACKUP_ACTION = "android.app.backup.intent.RUN";
    public static final String RUN_INITIALIZE_ACTION = "android.app.backup.intent.INIT";
    private static final int SCHEDULE_FILE_VERSION = 1;
    private static final String SERVICE_ACTION_TRANSPORT_HOST = "android.backup.TRANSPORT_HOST";
    public static final String SETTINGS_PACKAGE = "com.android.providers.settings";
    public static final String SHARED_BACKUP_AGENT_PACKAGE = "com.android.sharedstoragebackup";
    public static final String TAG = "BackupManagerService";
    private static final long TIMEOUT_FULL_CONFIRMATION = 60000;
    private static final long TIMEOUT_INTERVAL = 10000;
    private static final long TRANSPORT_RETRY_INTERVAL = 3600000;
    static Trampoline sInstance;
    private ActiveRestoreSession mActiveRestoreSession;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;
    private AlarmManager mAlarmManager;
    private boolean mAutoRestore;
    private BackupHandler mBackupHandler;
    private IBackupManager mBackupManagerBinder;
    private final BackupPasswordManager mBackupPasswordManager;
    private BackupPolicyEnforcer mBackupPolicyEnforcer;
    private volatile boolean mBackupRunning;
    private File mBaseStateDir;
    private volatile boolean mClearingData;
    private IBackupAgent mConnectedAgent;
    private volatile boolean mConnecting;
    private BackupManagerConstants mConstants;
    private Context mContext;
    private File mDataDir;
    private boolean mEnabled;
    @GuardedBy("mQueueLock")
    private ArrayList<FullBackupEntry> mFullBackupQueue;
    private File mFullBackupScheduleFile;
    @GuardedBy("mPendingRestores")
    private boolean mIsRestoreInProgress;
    private DataChangedJournal mJournal;
    private File mJournalDir;
    private volatile long mLastBackupPass;
    private PackageManager mPackageManager;
    private PowerManager mPowerManager;
    private ProcessedPackagesJournal mProcessedPackagesJournal;
    private boolean mProvisioned;
    private ContentObserver mProvisionedObserver;
    private final long mRegisterTransportsRequestedTime;
    private PendingIntent mRunBackupIntent;
    private BroadcastReceiver mRunBackupReceiver;
    private PendingIntent mRunInitIntent;
    private BroadcastReceiver mRunInitReceiver;
    @GuardedBy("mQueueLock")
    private PerformFullTransportBackupTask mRunningFullBackupTask;
    private File mTokenFile;
    private final TransportManager mTransportManager;
    private PowerManager.WakeLock mWakelock;
    private final SparseArray<HashSet<String>> mBackupParticipants = new SparseArray<>();
    private HashMap<String, BackupRequest> mPendingBackups = new HashMap<>();
    private final Object mQueueLock = new Object();
    private final Object mAgentConnectLock = new Object();
    private final List<String> mBackupTrace = new ArrayList();
    private final Object mClearDataLock = new Object();
    @GuardedBy("mPendingRestores")
    private final Queue<PerformUnifiedRestoreTask> mPendingRestores = new ArrayDeque();
    @GuardedBy("mCurrentOpLock")
    private final SparseArray<Operation> mCurrentOperations = new SparseArray<>();
    private final Object mCurrentOpLock = new Object();
    private final Random mTokenGenerator = new Random();
    final AtomicInteger mNextToken = new AtomicInteger();
    private final SparseArray<AdbParams> mAdbBackupRestoreConfirmations = new SparseArray<>();
    private final SecureRandom mRng = new SecureRandom();
    private Set<String> mAncestralPackages = null;
    private long mAncestralToken = 0;
    private long mCurrentToken = 0;
    private final ArraySet<String> mPendingInits = new ArraySet<>();
    private Runnable mFullBackupScheduleWriter = new Runnable() { // from class: com.android.server.backup.BackupManagerService.1
        @Override // java.lang.Runnable
        public void run() {
            synchronized (BackupManagerService.this.mQueueLock) {
                try {
                    ByteArrayOutputStream bufStream = new ByteArrayOutputStream(4096);
                    DataOutputStream bufOut = new DataOutputStream(bufStream);
                    bufOut.writeInt(1);
                    int N = BackupManagerService.this.mFullBackupQueue.size();
                    bufOut.writeInt(N);
                    for (int i = 0; i < N; i++) {
                        FullBackupEntry entry = (FullBackupEntry) BackupManagerService.this.mFullBackupQueue.get(i);
                        bufOut.writeUTF(entry.packageName);
                        bufOut.writeLong(entry.lastBackup);
                    }
                    bufOut.flush();
                    AtomicFile af = new AtomicFile(BackupManagerService.this.mFullBackupScheduleFile);
                    FileOutputStream out = af.startWrite();
                    out.write(bufStream.toByteArray());
                    af.finishWrite(out);
                } catch (Exception e) {
                    Slog.e(BackupManagerService.TAG, "Unable to write backup schedule!", e);
                }
            }
        }
    };
    private BroadcastReceiver mBroadcastReceiver = new AnonymousClass2();
    private IPackageManager mPackageManagerBinder = AppGlobals.getPackageManager();
    private IActivityManager mActivityManager = ActivityManager.getService();
    private IStorageManager mStorageManager = IStorageManager.Stub.asInterface(ServiceManager.getService("mount"));

    /* JADX INFO: Access modifiers changed from: package-private */
    public static Trampoline getInstance() {
        return sInstance;
    }

    public BackupManagerConstants getConstants() {
        return this.mConstants;
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public BackupAgentTimeoutParameters getAgentTimeoutParameters() {
        return this.mAgentTimeoutParameters;
    }

    public Context getContext() {
        return this.mContext;
    }

    public void setContext(Context context) {
        this.mContext = context;
    }

    public PackageManager getPackageManager() {
        return this.mPackageManager;
    }

    public void setPackageManager(PackageManager packageManager) {
        this.mPackageManager = packageManager;
    }

    public IPackageManager getPackageManagerBinder() {
        return this.mPackageManagerBinder;
    }

    public void setPackageManagerBinder(IPackageManager packageManagerBinder) {
        this.mPackageManagerBinder = packageManagerBinder;
    }

    public IActivityManager getActivityManager() {
        return this.mActivityManager;
    }

    public void setActivityManager(IActivityManager activityManager) {
        this.mActivityManager = activityManager;
    }

    public AlarmManager getAlarmManager() {
        return this.mAlarmManager;
    }

    public void setAlarmManager(AlarmManager alarmManager) {
        this.mAlarmManager = alarmManager;
    }

    public void setBackupManagerBinder(IBackupManager backupManagerBinder) {
        this.mBackupManagerBinder = backupManagerBinder;
    }

    public TransportManager getTransportManager() {
        return this.mTransportManager;
    }

    public boolean isEnabled() {
        return this.mEnabled;
    }

    public void setEnabled(boolean enabled) {
        this.mEnabled = enabled;
    }

    public boolean isProvisioned() {
        return this.mProvisioned;
    }

    public void setProvisioned(boolean provisioned) {
        this.mProvisioned = provisioned;
    }

    public PowerManager.WakeLock getWakelock() {
        return this.mWakelock;
    }

    public void setWakelock(PowerManager.WakeLock wakelock) {
        this.mWakelock = wakelock;
    }

    public Handler getBackupHandler() {
        return this.mBackupHandler;
    }

    public void setBackupHandler(BackupHandler backupHandler) {
        this.mBackupHandler = backupHandler;
    }

    public PendingIntent getRunInitIntent() {
        return this.mRunInitIntent;
    }

    public void setRunInitIntent(PendingIntent runInitIntent) {
        this.mRunInitIntent = runInitIntent;
    }

    public HashMap<String, BackupRequest> getPendingBackups() {
        return this.mPendingBackups;
    }

    public void setPendingBackups(HashMap<String, BackupRequest> pendingBackups) {
        this.mPendingBackups = pendingBackups;
    }

    public Object getQueueLock() {
        return this.mQueueLock;
    }

    public boolean isBackupRunning() {
        return this.mBackupRunning;
    }

    public void setBackupRunning(boolean backupRunning) {
        this.mBackupRunning = backupRunning;
    }

    public long getLastBackupPass() {
        return this.mLastBackupPass;
    }

    public void setLastBackupPass(long lastBackupPass) {
        this.mLastBackupPass = lastBackupPass;
    }

    public Object getClearDataLock() {
        return this.mClearDataLock;
    }

    public boolean isClearingData() {
        return this.mClearingData;
    }

    public void setClearingData(boolean clearingData) {
        this.mClearingData = clearingData;
    }

    public boolean isRestoreInProgress() {
        return this.mIsRestoreInProgress;
    }

    public void setRestoreInProgress(boolean restoreInProgress) {
        this.mIsRestoreInProgress = restoreInProgress;
    }

    public Queue<PerformUnifiedRestoreTask> getPendingRestores() {
        return this.mPendingRestores;
    }

    public ActiveRestoreSession getActiveRestoreSession() {
        return this.mActiveRestoreSession;
    }

    public void setActiveRestoreSession(ActiveRestoreSession activeRestoreSession) {
        this.mActiveRestoreSession = activeRestoreSession;
    }

    public SparseArray<Operation> getCurrentOperations() {
        return this.mCurrentOperations;
    }

    public Object getCurrentOpLock() {
        return this.mCurrentOpLock;
    }

    public SparseArray<AdbParams> getAdbBackupRestoreConfirmations() {
        return this.mAdbBackupRestoreConfirmations;
    }

    public File getBaseStateDir() {
        return this.mBaseStateDir;
    }

    public void setBaseStateDir(File baseStateDir) {
        this.mBaseStateDir = baseStateDir;
    }

    public File getDataDir() {
        return this.mDataDir;
    }

    public void setDataDir(File dataDir) {
        this.mDataDir = dataDir;
    }

    public DataChangedJournal getJournal() {
        return this.mJournal;
    }

    public void setJournal(DataChangedJournal journal) {
        this.mJournal = journal;
    }

    public SecureRandom getRng() {
        return this.mRng;
    }

    public Set<String> getAncestralPackages() {
        return this.mAncestralPackages;
    }

    public void setAncestralPackages(Set<String> ancestralPackages) {
        this.mAncestralPackages = ancestralPackages;
    }

    public long getAncestralToken() {
        return this.mAncestralToken;
    }

    public void setAncestralToken(long ancestralToken) {
        this.mAncestralToken = ancestralToken;
    }

    public long getCurrentToken() {
        return this.mCurrentToken;
    }

    public void setCurrentToken(long currentToken) {
        this.mCurrentToken = currentToken;
    }

    public ArraySet<String> getPendingInits() {
        return this.mPendingInits;
    }

    public void clearPendingInits() {
        this.mPendingInits.clear();
    }

    public PerformFullTransportBackupTask getRunningFullBackupTask() {
        return this.mRunningFullBackupTask;
    }

    public void setRunningFullBackupTask(PerformFullTransportBackupTask runningFullBackupTask) {
        this.mRunningFullBackupTask = runningFullBackupTask;
    }

    /* loaded from: classes.dex */
    public static final class Lifecycle extends SystemService {
        public Lifecycle(Context context) {
            super(context);
            BackupManagerService.sInstance = new Trampoline(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            publishBinderService(BatteryService.HealthServiceWrapper.INSTANCE_HEALTHD, BackupManagerService.sInstance);
        }

        @Override // com.android.server.SystemService
        public void onUnlockUser(int userId) {
            if (userId == 0) {
                BackupManagerService.sInstance.unlockSystemUser();
            }
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void unlockSystemUser() {
        Trace.traceBegin(64L, "backup migrate");
        if (!backupSettingMigrated(0)) {
            Slog.i(TAG, "Backup enable apparently not migrated");
            ContentResolver r = sInstance.mContext.getContentResolver();
            int enableState = Settings.Secure.getIntForUser(r, BACKUP_ENABLE_FILE, -1, 0);
            if (enableState >= 0) {
                StringBuilder sb = new StringBuilder();
                sb.append("Migrating enable state ");
                sb.append(enableState != 0);
                Slog.i(TAG, sb.toString());
                writeBackupEnableState(enableState != 0, 0);
                Settings.Secure.putStringForUser(r, BACKUP_ENABLE_FILE, null, 0);
            } else {
                Slog.i(TAG, "Backup not yet configured; retaining null enable state");
            }
        }
        Trace.traceEnd(64L);
        Trace.traceBegin(64L, "backup enable");
        try {
            sInstance.setBackupEnabled(readBackupEnableState(0));
        } catch (RemoteException e) {
        }
        Trace.traceEnd(64L);
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public int generateRandomIntegerToken() {
        int token = this.mTokenGenerator.nextInt();
        if (token < 0) {
            token = -token;
        }
        return (token & (-256)) | (this.mNextToken.incrementAndGet() & 255);
    }

    public PackageManagerBackupAgent makeMetadataAgent() {
        PackageManagerBackupAgent pmAgent = new PackageManagerBackupAgent(this.mPackageManager);
        pmAgent.attach(this.mContext);
        pmAgent.onCreate();
        return pmAgent;
    }

    public PackageManagerBackupAgent makeMetadataAgent(List<PackageInfo> packages) {
        PackageManagerBackupAgent pmAgent = new PackageManagerBackupAgent(this.mPackageManager, packages);
        pmAgent.attach(this.mContext);
        pmAgent.onCreate();
        return pmAgent;
    }

    public void addBackupTrace(String s) {
        synchronized (this.mBackupTrace) {
            this.mBackupTrace.add(s);
        }
    }

    public void clearBackupTrace() {
        synchronized (this.mBackupTrace) {
            this.mBackupTrace.clear();
        }
    }

    public static BackupManagerService create(Context context, Trampoline parent, HandlerThread backupThread) {
        SystemConfig systemConfig = SystemConfig.getInstance();
        Set<ComponentName> transportWhitelist = systemConfig.getBackupTransportWhitelist();
        if (transportWhitelist == null) {
            transportWhitelist = Collections.emptySet();
        }
        String transport = Settings.Secure.getString(context.getContentResolver(), "backup_transport");
        if (TextUtils.isEmpty(transport)) {
            transport = null;
        }
        Slog.v(TAG, "Starting with transport " + transport);
        TransportManager transportManager = new TransportManager(context, transportWhitelist, transport);
        File baseStateDir = new File(Environment.getDataDirectory(), BatteryService.HealthServiceWrapper.INSTANCE_HEALTHD);
        File dataDir = new File(Environment.getDownloadCacheDirectory(), "backup_stage");
        return new BackupManagerService(context, parent, backupThread, baseStateDir, dataDir, transportManager);
    }

    @VisibleForTesting
    BackupManagerService(Context context, Trampoline parent, HandlerThread backupThread, File baseStateDir, File dataDir, TransportManager transportManager) {
        this.mContext = context;
        this.mPackageManager = context.getPackageManager();
        this.mAlarmManager = (AlarmManager) context.getSystemService("alarm");
        this.mPowerManager = (PowerManager) context.getSystemService("power");
        this.mBackupManagerBinder = Trampoline.asInterface(parent.asBinder());
        this.mAgentTimeoutParameters = new BackupAgentTimeoutParameters(Handler.getMain(), this.mContext.getContentResolver());
        this.mAgentTimeoutParameters.start();
        this.mBackupHandler = new BackupHandler(this, backupThread.getLooper());
        ContentResolver resolver = context.getContentResolver();
        this.mProvisioned = Settings.Global.getInt(resolver, "device_provisioned", 0) != 0;
        this.mAutoRestore = Settings.Secure.getInt(resolver, "backup_auto_restore", 1) != 0;
        this.mProvisionedObserver = new ProvisionedObserver(this, this.mBackupHandler);
        resolver.registerContentObserver(Settings.Global.getUriFor("device_provisioned"), false, this.mProvisionedObserver);
        this.mBaseStateDir = baseStateDir;
        this.mBaseStateDir.mkdirs();
        if (!SELinux.restorecon(this.mBaseStateDir)) {
            Slog.e(TAG, "SELinux restorecon failed on " + this.mBaseStateDir);
        }
        this.mDataDir = dataDir;
        this.mBackupPasswordManager = new BackupPasswordManager(this.mContext, this.mBaseStateDir, this.mRng);
        this.mRunBackupReceiver = new RunBackupReceiver(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(RUN_BACKUP_ACTION);
        context.registerReceiver(this.mRunBackupReceiver, filter, "android.permission.BACKUP", null);
        this.mRunInitReceiver = new RunInitializeReceiver(this);
        IntentFilter filter2 = new IntentFilter();
        filter2.addAction(RUN_INITIALIZE_ACTION);
        context.registerReceiver(this.mRunInitReceiver, filter2, "android.permission.BACKUP", null);
        Intent backupIntent = new Intent(RUN_BACKUP_ACTION);
        backupIntent.addFlags(1073741824);
        this.mRunBackupIntent = PendingIntent.getBroadcast(context, 0, backupIntent, 0);
        Intent initIntent = new Intent(RUN_INITIALIZE_ACTION);
        initIntent.addFlags(1073741824);
        this.mRunInitIntent = PendingIntent.getBroadcast(context, 0, initIntent, 0);
        this.mJournalDir = new File(this.mBaseStateDir, "pending");
        this.mJournalDir.mkdirs();
        this.mJournal = null;
        this.mConstants = new BackupManagerConstants(this.mBackupHandler, this.mContext.getContentResolver());
        this.mConstants.start();
        this.mFullBackupScheduleFile = new File(this.mBaseStateDir, "fb-schedule");
        initPackageTracking();
        synchronized (this.mBackupParticipants) {
            try {
                addPackageParticipantsLocked(null);
            } catch (Throwable th) {
                th = th;
                while (true) {
                    try {
                        break;
                    } catch (Throwable th2) {
                        th = th2;
                    }
                }
                throw th;
            }
        }
        this.mTransportManager = transportManager;
        this.mTransportManager.setOnTransportRegisteredListener(new OnTransportRegisteredListener() { // from class: com.android.server.backup.-$$Lambda$BackupManagerService$QlgHuOXOPKAZpwyUhPFAintPnqM
            @Override // com.android.server.backup.transport.OnTransportRegisteredListener
            public final void onTransportRegistered(String str, String str2) {
                BackupManagerService.this.onTransportRegistered(str, str2);
            }
        });
        this.mRegisterTransportsRequestedTime = SystemClock.elapsedRealtime();
        BackupHandler backupHandler = this.mBackupHandler;
        final TransportManager transportManager2 = this.mTransportManager;
        Objects.requireNonNull(transportManager2);
        backupHandler.postDelayed(new Runnable() { // from class: com.android.server.backup.-$$Lambda$pM_c5tVAGDtxjxLF_ONtACWWq6Q
            @Override // java.lang.Runnable
            public final void run() {
                TransportManager.this.registerTransports();
            }
        }, INITIALIZATION_DELAY_MILLIS);
        this.mBackupHandler.postDelayed(new Runnable() { // from class: com.android.server.backup.-$$Lambda$BackupManagerService$7naKh6MW6ryzdPxgJfM5jV1nHp4
            @Override // java.lang.Runnable
            public final void run() {
                BackupManagerService.this.parseLeftoverJournals();
            }
        }, INITIALIZATION_DELAY_MILLIS);
        this.mWakelock = this.mPowerManager.newWakeLock(1, "*backup*");
        this.mBackupPolicyEnforcer = new BackupPolicyEnforcer(context);
    }

    private void initPackageTracking() {
        this.mTokenFile = new File(this.mBaseStateDir, "ancestral");
        try {
            DataInputStream tokenStream = new DataInputStream(new BufferedInputStream(new FileInputStream(this.mTokenFile)));
            try {
                int version = tokenStream.readInt();
                if (version == 1) {
                    this.mAncestralToken = tokenStream.readLong();
                    this.mCurrentToken = tokenStream.readLong();
                    int numPackages = tokenStream.readInt();
                    if (numPackages >= 0) {
                        this.mAncestralPackages = new HashSet();
                        for (int i = 0; i < numPackages; i++) {
                            String pkgName = tokenStream.readUTF();
                            this.mAncestralPackages.add(pkgName);
                        }
                    }
                }
                $closeResource(null, tokenStream);
            } finally {
            }
        } catch (FileNotFoundException e) {
            Slog.v(TAG, "No ancestral data");
        } catch (IOException e2) {
            Slog.w(TAG, "Unable to read token file", e2);
        }
        this.mProcessedPackagesJournal = new ProcessedPackagesJournal(this.mBaseStateDir);
        this.mProcessedPackagesJournal.init();
        synchronized (this.mQueueLock) {
            this.mFullBackupQueue = readFullBackupSchedule();
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.PACKAGE_ADDED");
        filter.addAction("android.intent.action.PACKAGE_REMOVED");
        filter.addAction("android.intent.action.PACKAGE_CHANGED");
        filter.addDataScheme("package");
        this.mContext.registerReceiver(this.mBroadcastReceiver, filter);
        IntentFilter sdFilter = new IntentFilter();
        sdFilter.addAction("android.intent.action.EXTERNAL_APPLICATIONS_AVAILABLE");
        sdFilter.addAction("android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE");
        this.mContext.registerReceiver(this.mBroadcastReceiver, sdFilter);
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

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Found unreachable blocks
        	at jadx.core.dex.visitors.blocks.DominatorTree.sortBlocks(DominatorTree.java:35)
        	at jadx.core.dex.visitors.blocks.DominatorTree.compute(DominatorTree.java:25)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.computeDominators(BlockProcessor.java:202)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:45)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    private java.util.ArrayList<com.android.server.backup.fullbackup.FullBackupEntry> readFullBackupSchedule() {
        /*
            Method dump skipped, instructions count: 447
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.BackupManagerService.readFullBackupSchedule():java.util.ArrayList");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void writeFullBackupScheduleAsync() {
        this.mBackupHandler.removeCallbacks(this.mFullBackupScheduleWriter);
        this.mBackupHandler.post(this.mFullBackupScheduleWriter);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void parseLeftoverJournals() {
        ArrayList<DataChangedJournal> journals = DataChangedJournal.listJournals(this.mJournalDir);
        Iterator<DataChangedJournal> it = journals.iterator();
        while (it.hasNext()) {
            DataChangedJournal journal = it.next();
            if (!journal.equals(this.mJournal)) {
                try {
                    journal.forEach(new DataChangedJournal.Consumer() { // from class: com.android.server.backup.-$$Lambda$BackupManagerService$-mOc1e-1SsZws3njOjKXfyubq98
                        @Override // com.android.server.backup.DataChangedJournal.Consumer
                        public final void accept(String str) {
                            BackupManagerService.lambda$parseLeftoverJournals$0(BackupManagerService.this, str);
                        }
                    });
                } catch (IOException e) {
                    Slog.e(TAG, "Can't read " + journal, e);
                }
            }
        }
    }

    public static /* synthetic */ void lambda$parseLeftoverJournals$0(BackupManagerService backupManagerService, String packageName) {
        Slog.i(TAG, "Found stale backup journal, scheduling");
        backupManagerService.dataChangedImpl(packageName);
    }

    public byte[] randomBytes(int bits) {
        byte[] array = new byte[bits / 8];
        this.mRng.nextBytes(array);
        return array;
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public boolean setBackupPassword(String currentPw, String newPw) {
        return this.mBackupPasswordManager.setBackupPassword(currentPw, newPw);
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public boolean hasBackupPassword() {
        return this.mBackupPasswordManager.hasBackupPassword();
    }

    public boolean backupPasswordMatches(String currentPw) {
        return this.mBackupPasswordManager.backupPasswordMatches(currentPw);
    }

    public void recordInitPending(boolean isPending, String transportName, String transportDirName) {
        synchronized (this.mQueueLock) {
            File stateDir = new File(this.mBaseStateDir, transportDirName);
            File initPendingFile = new File(stateDir, INIT_SENTINEL_FILE_NAME);
            if (isPending) {
                this.mPendingInits.add(transportName);
                try {
                    new FileOutputStream(initPendingFile).close();
                } catch (IOException e) {
                }
            } else {
                initPendingFile.delete();
                this.mPendingInits.remove(transportName);
            }
        }
    }

    public void resetBackupState(File stateFileDir) {
        File[] listFiles;
        int i;
        synchronized (this.mQueueLock) {
            this.mProcessedPackagesJournal.reset();
            this.mCurrentToken = 0L;
            writeRestoreTokens();
            i = 0;
            for (File sf : stateFileDir.listFiles()) {
                if (!sf.getName().equals(INIT_SENTINEL_FILE_NAME)) {
                    sf.delete();
                }
            }
        }
        synchronized (this.mBackupParticipants) {
            int N = this.mBackupParticipants.size();
            while (true) {
                int i2 = i;
                if (i2 < N) {
                    HashSet<String> participants = this.mBackupParticipants.valueAt(i2);
                    if (participants != null) {
                        Iterator<String> it = participants.iterator();
                        while (it.hasNext()) {
                            String packageName = it.next();
                            dataChangedImpl(packageName);
                        }
                    }
                    i = i2 + 1;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onTransportRegistered(String transportName, String transportDirName) {
        long timeMs = SystemClock.elapsedRealtime() - this.mRegisterTransportsRequestedTime;
        Slog.d(TAG, "Transport " + transportName + " registered " + timeMs + "ms after first request (delay = " + INITIALIZATION_DELAY_MILLIS + "ms)");
        File stateDir = new File(this.mBaseStateDir, transportDirName);
        stateDir.mkdirs();
        File initSentinel = new File(stateDir, INIT_SENTINEL_FILE_NAME);
        if (initSentinel.exists()) {
            synchronized (this.mQueueLock) {
                this.mPendingInits.add(transportName);
                this.mAlarmManager.set(0, System.currentTimeMillis() + 60000, this.mRunInitIntent);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.backup.BackupManagerService$2  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass2 extends BroadcastReceiver {
        AnonymousClass2() {
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action;
            String action2 = intent.getAction();
            boolean replacing = false;
            boolean added = false;
            boolean added2 = false;
            Bundle extras = intent.getExtras();
            int i = 0;
            if ("android.intent.action.PACKAGE_ADDED".equals(action2) || "android.intent.action.PACKAGE_REMOVED".equals(action2) || "android.intent.action.PACKAGE_CHANGED".equals(action2)) {
                Uri uri = intent.getData();
                if (uri == null) {
                    return;
                }
                final String pkgName = uri.getSchemeSpecificPart();
                pkgList = pkgName != null ? new String[]{pkgName} : null;
                added2 = "android.intent.action.PACKAGE_CHANGED".equals(action2);
                if (added2) {
                    final String[] components = intent.getStringArrayExtra("android.intent.extra.changed_component_name_list");
                    BackupManagerService.this.mBackupHandler.post(new Runnable() { // from class: com.android.server.backup.-$$Lambda$BackupManagerService$2$k3_lOimiIJDhWdG7_SCrtoKbtjY
                        @Override // java.lang.Runnable
                        public final void run() {
                            BackupManagerService.this.mTransportManager.onPackageChanged(pkgName, components);
                        }
                    });
                    return;
                }
                added = "android.intent.action.PACKAGE_ADDED".equals(action2);
                replacing = extras.getBoolean("android.intent.extra.REPLACING", false);
            } else if ("android.intent.action.EXTERNAL_APPLICATIONS_AVAILABLE".equals(action2)) {
                added = true;
                pkgList = intent.getStringArrayExtra("android.intent.extra.changed_package_list");
            } else if ("android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE".equals(action2)) {
                added = false;
                pkgList = intent.getStringArrayExtra("android.intent.extra.changed_package_list");
            }
            boolean added3 = added;
            boolean replacing2 = replacing;
            if (pkgList != null && pkgList.length != 0) {
                int uid = extras.getInt("android.intent.extra.UID");
                if (added3) {
                    synchronized (BackupManagerService.this.mBackupParticipants) {
                        if (replacing2) {
                            try {
                                BackupManagerService.this.removePackageParticipantsLocked(pkgList, uid);
                            } catch (Throwable th) {
                                th = th;
                                while (true) {
                                    try {
                                        break;
                                    } catch (Throwable th2) {
                                        th = th2;
                                    }
                                }
                                throw th;
                            }
                        }
                        try {
                            BackupManagerService.this.addPackageParticipantsLocked(pkgList);
                            long now = System.currentTimeMillis();
                            int length = pkgList.length;
                            int i2 = 0;
                            while (i2 < length) {
                                final String packageName = pkgList[i2];
                                try {
                                    PackageInfo app = BackupManagerService.this.mPackageManager.getPackageInfo(packageName, i);
                                    if (AppBackupUtils.appGetsFullBackup(app) && AppBackupUtils.appIsEligibleForBackup(app.applicationInfo, BackupManagerService.this.mPackageManager)) {
                                        BackupManagerService.this.enqueueFullBackup(packageName, now);
                                        action = action2;
                                        try {
                                            BackupManagerService.this.scheduleNextFullBackupJob(0L);
                                        } catch (PackageManager.NameNotFoundException e) {
                                            Slog.w(BackupManagerService.TAG, "Can't resolve new app " + packageName);
                                            i2++;
                                            action2 = action;
                                            i = 0;
                                        }
                                    } else {
                                        action = action2;
                                        synchronized (BackupManagerService.this.mQueueLock) {
                                            BackupManagerService.this.dequeueFullBackupLocked(packageName);
                                        }
                                        BackupManagerService.this.writeFullBackupScheduleAsync();
                                    }
                                    BackupManagerService.this.mBackupHandler.post(new Runnable() { // from class: com.android.server.backup.-$$Lambda$BackupManagerService$2$8WilE3DKM3p1qJhvhqvZiHtD9hI
                                        @Override // java.lang.Runnable
                                        public final void run() {
                                            BackupManagerService.this.mTransportManager.onPackageAdded(packageName);
                                        }
                                    });
                                } catch (PackageManager.NameNotFoundException e2) {
                                    action = action2;
                                }
                                i2++;
                                action2 = action;
                                i = 0;
                            }
                            BackupManagerService.this.dataChangedImpl(BackupManagerService.PACKAGE_MANAGER_SENTINEL);
                            return;
                        } catch (Throwable th3) {
                            th = th3;
                            while (true) {
                                break;
                                break;
                            }
                            throw th;
                        }
                    }
                }
                if (!replacing2) {
                    synchronized (BackupManagerService.this.mBackupParticipants) {
                        BackupManagerService.this.removePackageParticipantsLocked(pkgList, uid);
                    }
                }
                for (final String pkgName2 : pkgList) {
                    BackupManagerService.this.mBackupHandler.post(new Runnable() { // from class: com.android.server.backup.-$$Lambda$BackupManagerService$2$PXK_S3ijBAkFZ4wQtjneIECynPo
                        @Override // java.lang.Runnable
                        public final void run() {
                            BackupManagerService.this.mTransportManager.onPackageRemoved(pkgName2);
                        }
                    });
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void addPackageParticipantsLocked(String[] packageNames) {
        List<PackageInfo> targetApps = allAgentPackages();
        if (packageNames != null) {
            for (String packageName : packageNames) {
                addPackageParticipantsLockedInner(packageName, targetApps);
            }
            return;
        }
        addPackageParticipantsLockedInner(null, targetApps);
    }

    private void addPackageParticipantsLockedInner(String packageName, List<PackageInfo> targetPkgs) {
        for (PackageInfo pkg : targetPkgs) {
            if (packageName == null || pkg.packageName.equals(packageName)) {
                int uid = pkg.applicationInfo.uid;
                HashSet<String> set = this.mBackupParticipants.get(uid);
                if (set == null) {
                    set = new HashSet<>();
                    this.mBackupParticipants.put(uid, set);
                }
                set.add(pkg.packageName);
                Message msg = this.mBackupHandler.obtainMessage(16, pkg.packageName);
                this.mBackupHandler.sendMessage(msg);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removePackageParticipantsLocked(String[] packageNames, int oldUid) {
        if (packageNames == null) {
            Slog.w(TAG, "removePackageParticipants with null list");
            return;
        }
        for (String pkg : packageNames) {
            HashSet<String> set = this.mBackupParticipants.get(oldUid);
            if (set != null && set.contains(pkg)) {
                removePackageFromSetLocked(set, pkg);
                if (set.isEmpty()) {
                    this.mBackupParticipants.remove(oldUid);
                }
            }
        }
    }

    private void removePackageFromSetLocked(HashSet<String> set, String packageName) {
        if (set.contains(packageName)) {
            set.remove(packageName);
            this.mPendingBackups.remove(packageName);
        }
    }

    private List<PackageInfo> allAgentPackages() {
        ApplicationInfo app;
        List<PackageInfo> packages = this.mPackageManager.getInstalledPackages(134217728);
        int N = packages.size();
        for (int a = N - 1; a >= 0; a--) {
            PackageInfo pkg = packages.get(a);
            try {
                app = pkg.applicationInfo;
            } catch (PackageManager.NameNotFoundException e) {
                packages.remove(a);
            }
            if ((app.flags & 32768) != 0 && app.backupAgentName != null && (app.flags & 67108864) == 0) {
                pkg.applicationInfo.sharedLibraryFiles = this.mPackageManager.getApplicationInfo(pkg.packageName, 1024).sharedLibraryFiles;
            }
            packages.remove(a);
        }
        return packages;
    }

    public void logBackupComplete(String packageName) {
        String[] backupFinishedNotificationReceivers;
        if (packageName.equals(PACKAGE_MANAGER_SENTINEL)) {
            return;
        }
        for (String receiver : this.mConstants.getBackupFinishedNotificationReceivers()) {
            Intent notification = new Intent();
            notification.setAction(BACKUP_FINISHED_ACTION);
            notification.setPackage(receiver);
            notification.addFlags(268435488);
            notification.putExtra("packageName", packageName);
            this.mContext.sendBroadcastAsUser(notification, UserHandle.OWNER);
        }
        this.mProcessedPackagesJournal.addPackage(packageName);
    }

    public void writeRestoreTokens() {
        try {
            RandomAccessFile af = new RandomAccessFile(this.mTokenFile, "rwd");
            af.writeInt(1);
            af.writeLong(this.mAncestralToken);
            af.writeLong(this.mCurrentToken);
            if (this.mAncestralPackages == null) {
                af.writeInt(-1);
            } else {
                af.writeInt(this.mAncestralPackages.size());
                Slog.v(TAG, "Ancestral packages:  " + this.mAncestralPackages.size());
                for (String pkgName : this.mAncestralPackages) {
                    af.writeUTF(pkgName);
                }
            }
            $closeResource(null, af);
        } catch (IOException e) {
            Slog.w(TAG, "Unable to write token file:", e);
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public IBackupAgent bindToAgentSynchronous(ApplicationInfo app, int mode) {
        IBackupAgent agent = null;
        synchronized (this.mAgentConnectLock) {
            this.mConnecting = true;
            this.mConnectedAgent = null;
            try {
                if (this.mActivityManager.bindBackupAgent(app.packageName, mode, 0)) {
                    Slog.d(TAG, "awaiting agent for " + app);
                    long timeoutMark = System.currentTimeMillis() + 10000;
                    while (this.mConnecting && this.mConnectedAgent == null && System.currentTimeMillis() < timeoutMark) {
                        try {
                            this.mAgentConnectLock.wait(5000L);
                        } catch (InterruptedException e) {
                            Slog.w(TAG, "Interrupted: " + e);
                            this.mConnecting = false;
                            this.mConnectedAgent = null;
                        }
                    }
                    if (this.mConnecting) {
                        Slog.w(TAG, "Timeout waiting for agent " + app);
                        this.mConnectedAgent = null;
                    }
                    Slog.i(TAG, "got agent " + this.mConnectedAgent);
                    agent = this.mConnectedAgent;
                }
            } catch (RemoteException e2) {
            }
        }
        if (agent == null) {
            try {
                this.mActivityManager.clearPendingBackup();
            } catch (RemoteException e3) {
            }
        }
        return agent;
    }

    public void clearApplicationDataSynchronous(String packageName, boolean keepSystemState) {
        try {
            PackageInfo info = this.mPackageManager.getPackageInfo(packageName, 0);
            if ((info.applicationInfo.flags & 64) == 0) {
                return;
            }
            ClearDataObserver observer = new ClearDataObserver(this);
            synchronized (this.mClearDataLock) {
                this.mClearingData = true;
                try {
                    this.mActivityManager.clearApplicationUserData(packageName, keepSystemState, observer, 0);
                } catch (RemoteException e) {
                }
                long timeoutMark = System.currentTimeMillis() + 10000;
                while (this.mClearingData && System.currentTimeMillis() < timeoutMark) {
                    try {
                        this.mClearDataLock.wait(5000L);
                    } catch (InterruptedException e2) {
                        this.mClearingData = false;
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException e3) {
            Slog.w(TAG, "Tried to clear data for " + packageName + " but not found");
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public long getAvailableRestoreToken(String packageName) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "getAvailableRestoreToken");
        long token = this.mAncestralToken;
        synchronized (this.mQueueLock) {
            if (this.mCurrentToken != 0 && this.mProcessedPackagesJournal.hasBeenProcessed(packageName)) {
                token = this.mCurrentToken;
            }
        }
        return token;
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public int requestBackup(String[] packages, IBackupObserver observer, int flags) {
        return requestBackup(packages, observer, null, flags);
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public int requestBackup(String[] packages, IBackupObserver observer, IBackupManagerMonitor monitor, int flags) {
        this.mContext.enforceCallingPermission("android.permission.BACKUP", "requestBackup");
        if (packages == null || packages.length < 1) {
            Slog.e(TAG, "No packages named for backup request");
            BackupObserverUtils.sendBackupFinished(observer, JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE);
            BackupManagerMonitorUtils.monitorEvent(monitor, 49, null, 1, null);
            throw new IllegalArgumentException("No packages are provided for backup");
        } else if (!this.mEnabled || !this.mProvisioned) {
            Slog.i(TAG, "Backup requested but e=" + this.mEnabled + " p=" + this.mProvisioned);
            BackupObserverUtils.sendBackupFinished(observer, -2001);
            int logTag = this.mProvisioned ? 13 : 14;
            BackupManagerMonitorUtils.monitorEvent(monitor, logTag, null, 3, null);
            return -2001;
        } else {
            try {
                String transportDirName = this.mTransportManager.getTransportDirName(this.mTransportManager.getCurrentTransportName());
                final TransportClient transportClient = this.mTransportManager.getCurrentTransportClientOrThrow("BMS.requestBackup()");
                OnTaskFinishedListener listener = new OnTaskFinishedListener() { // from class: com.android.server.backup.-$$Lambda$BackupManagerService$d1gjNfZ3ZYIuaY4s01CFoLZa4Z0
                    @Override // com.android.server.backup.internal.OnTaskFinishedListener
                    public final void onFinished(String str) {
                        BackupManagerService.this.mTransportManager.disposeOfTransportClient(transportClient, str);
                    }
                };
                ArrayList<String> fullBackupList = new ArrayList<>();
                ArrayList<String> kvBackupList = new ArrayList<>();
                for (String packageName : packages) {
                    if (PACKAGE_MANAGER_SENTINEL.equals(packageName)) {
                        kvBackupList.add(packageName);
                    } else {
                        try {
                            PackageInfo packageInfo = this.mPackageManager.getPackageInfo(packageName, 134217728);
                            if (!AppBackupUtils.appIsEligibleForBackup(packageInfo.applicationInfo, this.mPackageManager)) {
                                BackupObserverUtils.sendBackupOnPackageResult(observer, packageName, -2001);
                            } else if (AppBackupUtils.appGetsFullBackup(packageInfo)) {
                                fullBackupList.add(packageInfo.packageName);
                            } else {
                                kvBackupList.add(packageInfo.packageName);
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            BackupObserverUtils.sendBackupOnPackageResult(observer, packageName, -2002);
                        }
                    }
                }
                EventLog.writeEvent((int) EventLogTags.BACKUP_REQUESTED, Integer.valueOf(packages.length), Integer.valueOf(kvBackupList.size()), Integer.valueOf(fullBackupList.size()));
                boolean nonIncrementalBackup = (flags & 1) != 0;
                Message msg = this.mBackupHandler.obtainMessage(15);
                msg.obj = new BackupParams(transportClient, transportDirName, kvBackupList, fullBackupList, observer, monitor, listener, true, nonIncrementalBackup);
                this.mBackupHandler.sendMessage(msg);
                return 0;
            } catch (TransportNotRegisteredException e2) {
                BackupObserverUtils.sendBackupFinished(observer, JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE);
                BackupManagerMonitorUtils.monitorEvent(monitor, 50, null, 1, null);
                return JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
            }
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void cancelBackups() {
        this.mContext.enforceCallingPermission("android.permission.BACKUP", "cancelBackups");
        long oldToken = Binder.clearCallingIdentity();
        try {
            List<Integer> operationsToCancel = new ArrayList<>();
            synchronized (this.mCurrentOpLock) {
                for (int i = 0; i < this.mCurrentOperations.size(); i++) {
                    Operation op = this.mCurrentOperations.valueAt(i);
                    int token = this.mCurrentOperations.keyAt(i);
                    if (op.type == 2) {
                        operationsToCancel.add(Integer.valueOf(token));
                    }
                }
            }
            for (Integer token2 : operationsToCancel) {
                handleCancel(token2.intValue(), true);
            }
            KeyValueBackupJob.schedule(this.mContext, 3600000L, this.mConstants);
            FullBackupJob.schedule(this.mContext, AppStandbyController.SettingsObserver.DEFAULT_SYSTEM_UPDATE_TIMEOUT, this.mConstants);
        } finally {
            Binder.restoreCallingIdentity(oldToken);
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void prepareOperationTimeout(int token, long interval, BackupRestoreTask callback, int operationType) {
        if (operationType != 0 && operationType != 1) {
            Slog.wtf(TAG, "prepareOperationTimeout() doesn't support operation " + Integer.toHexString(token) + " of type " + operationType);
            return;
        }
        synchronized (this.mCurrentOpLock) {
            this.mCurrentOperations.put(token, new Operation(0, callback, operationType));
            Message msg = this.mBackupHandler.obtainMessage(getMessageIdForOperationType(operationType), token, 0, callback);
            this.mBackupHandler.sendMessageDelayed(msg, interval);
        }
    }

    private int getMessageIdForOperationType(int operationType) {
        switch (operationType) {
            case 0:
                return 17;
            case 1:
                return 18;
            default:
                Slog.wtf(TAG, "getMessageIdForOperationType called on invalid operation type: " + operationType);
                return -1;
        }
    }

    public void removeOperation(int token) {
        synchronized (this.mCurrentOpLock) {
            if (this.mCurrentOperations.get(token) == null) {
                Slog.w(TAG, "Duplicate remove for operation. token=" + Integer.toHexString(token));
            }
            this.mCurrentOperations.remove(token);
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public boolean waitUntilOperationComplete(int token) {
        Operation op;
        int finalState = 0;
        synchronized (this.mCurrentOpLock) {
            while (true) {
                op = this.mCurrentOperations.get(token);
                if (op == null) {
                    break;
                } else if (op.state == 0) {
                    try {
                        this.mCurrentOpLock.wait();
                    } catch (InterruptedException e) {
                    }
                } else {
                    finalState = op.state;
                    break;
                }
            }
        }
        removeOperation(token);
        if (op != null) {
            this.mBackupHandler.removeMessages(getMessageIdForOperationType(op.type));
        }
        return finalState == 1;
    }

    public void handleCancel(int token, boolean cancelAll) {
        Operation op;
        synchronized (this.mCurrentOpLock) {
            op = this.mCurrentOperations.get(token);
            int state = op != null ? op.state : -1;
            if (state == 1) {
                Slog.w(TAG, "Operation already got an ack.Should have been removed from mCurrentOperations.");
                op = null;
                this.mCurrentOperations.delete(token);
            } else if (state == 0) {
                Slog.v(TAG, "Cancel: token=" + Integer.toHexString(token));
                op.state = -1;
                if (op.type == 0 || op.type == 1) {
                    this.mBackupHandler.removeMessages(getMessageIdForOperationType(op.type));
                }
            }
            this.mCurrentOpLock.notifyAll();
        }
        if (op != null && op.callback != null) {
            op.callback.handleCancel(cancelAll);
        }
    }

    public boolean isBackupOperationInProgress() {
        synchronized (this.mCurrentOpLock) {
            for (int i = 0; i < this.mCurrentOperations.size(); i++) {
                Operation op = this.mCurrentOperations.valueAt(i);
                if (op.type == 2 && op.state == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void tearDownAgentAndKill(ApplicationInfo app) {
        if (app == null) {
            return;
        }
        try {
            this.mActivityManager.unbindBackupAgent(app);
            if (app.uid >= 10000 && !app.packageName.equals("com.android.backupconfirm")) {
                this.mActivityManager.killApplicationProcess(app.processName, app.uid);
            }
        } catch (RemoteException e) {
            Slog.d(TAG, "Lost app trying to shut down");
        }
    }

    public boolean deviceIsEncrypted() {
        try {
            if (this.mStorageManager.getEncryptionState() != 1) {
                if (this.mStorageManager.getPasswordType() != 1) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            Slog.e(TAG, "Unable to communicate with storagemanager service: " + e.getMessage());
            return true;
        }
    }

    public void scheduleNextFullBackupJob(long transportMinLatency) {
        synchronized (this.mQueueLock) {
            try {
                try {
                    if (this.mFullBackupQueue.size() > 0) {
                        long upcomingLastBackup = this.mFullBackupQueue.get(0).lastBackup;
                        long timeSinceLast = System.currentTimeMillis() - upcomingLastBackup;
                        long interval = this.mConstants.getFullBackupIntervalMilliseconds();
                        long appLatency = timeSinceLast < interval ? interval - timeSinceLast : 0L;
                        final long latency = Math.max(transportMinLatency, appLatency);
                        Runnable r = new Runnable() { // from class: com.android.server.backup.BackupManagerService.3
                            @Override // java.lang.Runnable
                            public void run() {
                                FullBackupJob.schedule(BackupManagerService.this.mContext, latency, BackupManagerService.this.mConstants);
                            }
                        };
                        this.mBackupHandler.postDelayed(r, 2500L);
                    } else {
                        Slog.i(TAG, "Full backup queue empty; not scheduling");
                    }
                } catch (Throwable th) {
                    th = th;
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mQueueLock")
    public void dequeueFullBackupLocked(String packageName) {
        int N = this.mFullBackupQueue.size();
        for (int i = N - 1; i >= 0; i--) {
            FullBackupEntry e = this.mFullBackupQueue.get(i);
            if (packageName.equals(e.packageName)) {
                this.mFullBackupQueue.remove(i);
            }
        }
    }

    public void enqueueFullBackup(String packageName, long lastBackedUp) {
        FullBackupEntry newEntry = new FullBackupEntry(packageName, lastBackedUp);
        synchronized (this.mQueueLock) {
            dequeueFullBackupLocked(packageName);
            int which = -1;
            if (lastBackedUp > 0) {
                int which2 = this.mFullBackupQueue.size() - 1;
                which = which2;
                while (true) {
                    if (which < 0) {
                        break;
                    }
                    FullBackupEntry entry = this.mFullBackupQueue.get(which);
                    if (entry.lastBackup > lastBackedUp) {
                        which--;
                    } else {
                        this.mFullBackupQueue.add(which + 1, newEntry);
                        break;
                    }
                }
            }
            if (which < 0) {
                this.mFullBackupQueue.add(0, newEntry);
            }
        }
        writeFullBackupScheduleAsync();
    }

    private boolean fullBackupAllowable(String transportName) {
        if (!this.mTransportManager.isTransportRegistered(transportName)) {
            Slog.w(TAG, "Transport not registered; full data backup not performed");
            return false;
        }
        try {
            String transportDirName = this.mTransportManager.getTransportDirName(transportName);
            File stateDir = new File(this.mBaseStateDir, transportDirName);
            File pmState = new File(stateDir, PACKAGE_MANAGER_SENTINEL);
            if (pmState.length() <= 0) {
                Slog.i(TAG, "Full backup requested but dataset not yet initialized");
                return false;
            }
            return true;
        } catch (Exception e) {
            Slog.w(TAG, "Unable to get transport name: " + e.getMessage());
            return false;
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:103:0x01ac  */
    /* JADX WARN: Removed duplicated region for block: B:104:0x01ae  */
    /* JADX WARN: Removed duplicated region for block: B:136:0x028b A[LOOP:0: B:167:0x0062->B:136:0x028b, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:176:0x00f4 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:190:0x01dc A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:89:0x017a  */
    @Override // com.android.server.backup.BackupManagerServiceInterface
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public boolean beginFullBackup(com.android.server.backup.FullBackupJob r36) {
        /*
            Method dump skipped, instructions count: 686
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.BackupManagerService.beginFullBackup(com.android.server.backup.FullBackupJob):boolean");
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void endFullBackup() {
        Runnable endFullBackupRunnable = new Runnable() { // from class: com.android.server.backup.BackupManagerService.5
            @Override // java.lang.Runnable
            public void run() {
                PerformFullTransportBackupTask pftbt = null;
                synchronized (BackupManagerService.this.mQueueLock) {
                    if (BackupManagerService.this.mRunningFullBackupTask != null) {
                        pftbt = BackupManagerService.this.mRunningFullBackupTask;
                    }
                }
                if (pftbt != null) {
                    Slog.i(BackupManagerService.TAG, "Telling running backup to stop");
                    pftbt.handleCancel(true);
                }
            }
        };
        new Thread(endFullBackupRunnable, "end-full-backup").start();
    }

    public void restoreWidgetData(String packageName, byte[] widgetData) {
        AppWidgetBackupBridge.restoreWidgetState(packageName, widgetData, 0);
    }

    public void dataChangedImpl(String packageName) {
        HashSet<String> targets = dataChangedTargets(packageName);
        dataChangedImpl(packageName, targets);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dataChangedImpl(String packageName, HashSet<String> targets) {
        if (targets == null) {
            Slog.w(TAG, "dataChanged but no participant pkg='" + packageName + "' uid=" + Binder.getCallingUid());
            return;
        }
        synchronized (this.mQueueLock) {
            if (targets.contains(packageName)) {
                BackupRequest req = new BackupRequest(packageName);
                if (this.mPendingBackups.put(packageName, req) == null) {
                    writeToJournalLocked(packageName);
                }
            }
        }
        KeyValueBackupJob.schedule(this.mContext, this.mConstants);
    }

    private HashSet<String> dataChangedTargets(String packageName) {
        HashSet<String> union;
        HashSet<String> hashSet;
        if (this.mContext.checkPermission("android.permission.BACKUP", Binder.getCallingPid(), Binder.getCallingUid()) == -1) {
            synchronized (this.mBackupParticipants) {
                hashSet = this.mBackupParticipants.get(Binder.getCallingUid());
            }
            return hashSet;
        } else if (PACKAGE_MANAGER_SENTINEL.equals(packageName)) {
            return Sets.newHashSet(new String[]{PACKAGE_MANAGER_SENTINEL});
        } else {
            synchronized (this.mBackupParticipants) {
                union = SparseArrayUtils.union(this.mBackupParticipants);
            }
            return union;
        }
    }

    private void writeToJournalLocked(String str) {
        try {
            if (this.mJournal == null) {
                this.mJournal = DataChangedJournal.newJournal(this.mJournalDir);
            }
            this.mJournal.addPackage(str);
        } catch (IOException e) {
            Slog.e(TAG, "Can't write " + str + " to backup journal", e);
            this.mJournal = null;
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void dataChanged(final String packageName) {
        int callingUserHandle = UserHandle.getCallingUserId();
        if (callingUserHandle != 0) {
            return;
        }
        final HashSet<String> targets = dataChangedTargets(packageName);
        if (targets == null) {
            Slog.w(TAG, "dataChanged but no participant pkg='" + packageName + "' uid=" + Binder.getCallingUid());
            return;
        }
        this.mBackupHandler.post(new Runnable() { // from class: com.android.server.backup.BackupManagerService.6
            @Override // java.lang.Runnable
            public void run() {
                BackupManagerService.this.dataChangedImpl(packageName, targets);
            }
        });
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void initializeTransports(String[] transportNames, IBackupObserver observer) {
        this.mContext.enforceCallingPermission("android.permission.BACKUP", "initializeTransport");
        Slog.v(TAG, "initializeTransport(): " + Arrays.asList(transportNames));
        long oldId = Binder.clearCallingIdentity();
        try {
            this.mWakelock.acquire();
            OnTaskFinishedListener listener = new OnTaskFinishedListener() { // from class: com.android.server.backup.-$$Lambda$BackupManagerService$uWCtISrzNRpV2diTzD5MWI0bdDM
                @Override // com.android.server.backup.internal.OnTaskFinishedListener
                public final void onFinished(String str) {
                    BackupManagerService.this.mWakelock.release();
                }
            };
            this.mBackupHandler.post(new PerformInitializeTask(this, transportNames, observer, listener));
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void clearBackupData(String transportName, String packageName) {
        HashSet<String> apps;
        Slog.v(TAG, "clearBackupData() of " + packageName + " on " + transportName);
        try {
            PackageInfo info = this.mPackageManager.getPackageInfo(packageName, 134217728);
            if (this.mContext.checkPermission("android.permission.BACKUP", Binder.getCallingPid(), Binder.getCallingUid()) == -1) {
                apps = this.mBackupParticipants.get(Binder.getCallingUid());
            } else {
                apps = this.mProcessedPackagesJournal.getPackagesCopy();
            }
            if (apps.contains(packageName)) {
                this.mBackupHandler.removeMessages(12);
                synchronized (this.mQueueLock) {
                    final TransportClient transportClient = this.mTransportManager.getTransportClient(transportName, "BMS.clearBackupData()");
                    if (transportClient == null) {
                        Message msg = this.mBackupHandler.obtainMessage(12, new ClearRetryParams(transportName, packageName));
                        this.mBackupHandler.sendMessageDelayed(msg, 3600000L);
                        return;
                    }
                    long oldId = Binder.clearCallingIdentity();
                    OnTaskFinishedListener listener = new OnTaskFinishedListener() { // from class: com.android.server.backup.-$$Lambda$BackupManagerService$drk8n83Z0hBmm5D4bbaFMr5WuzA
                        @Override // com.android.server.backup.internal.OnTaskFinishedListener
                        public final void onFinished(String str) {
                            BackupManagerService.this.mTransportManager.disposeOfTransportClient(transportClient, str);
                        }
                    };
                    this.mWakelock.acquire();
                    Message msg2 = this.mBackupHandler.obtainMessage(4, new ClearParams(transportClient, info, listener));
                    this.mBackupHandler.sendMessage(msg2);
                    Binder.restoreCallingIdentity(oldId);
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.d(TAG, "No such package '" + packageName + "' - not clearing backup data");
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void backupNow() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "backupNow");
        PowerSaveState result = this.mPowerManager.getPowerSaveState(5);
        if (result.batterySaverEnabled) {
            Slog.v(TAG, "Not running backup while in battery save mode");
            KeyValueBackupJob.schedule(this.mContext, this.mConstants);
            return;
        }
        Slog.v(TAG, "Scheduling immediate backup pass");
        synchronized (this.mQueueLock) {
            try {
                this.mRunBackupIntent.send();
            } catch (PendingIntent.CanceledException e) {
                Slog.e(TAG, "run-backup intent cancelled!");
            }
            KeyValueBackupJob.cancel(this.mContext);
        }
    }

    public boolean deviceIsProvisioned() {
        ContentResolver resolver = this.mContext.getContentResolver();
        return Settings.Global.getInt(resolver, "device_provisioned", 0) != 0;
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void adbBackup(ParcelFileDescriptor fd, boolean includeApks, boolean includeObbs, boolean includeShared, boolean doWidgets, boolean doAllApps, boolean includeSystem, boolean compress, boolean doKeyValue, String[] pkgList) {
        long oldId;
        Throwable th;
        this.mContext.enforceCallingPermission("android.permission.BACKUP", "adbBackup");
        int callingUserHandle = UserHandle.getCallingUserId();
        if (callingUserHandle != 0) {
            throw new IllegalStateException("Backup supported only for the device owner");
        }
        if (!doAllApps && !includeShared && (pkgList == null || pkgList.length == 0)) {
            throw new IllegalArgumentException("Backup requested but neither shared nor any apps named");
        }
        long oldId2 = Binder.clearCallingIdentity();
        try {
            if (deviceIsProvisioned()) {
                Slog.v(TAG, "Requesting backup: apks=" + includeApks + " obb=" + includeObbs + " shared=" + includeShared + " all=" + doAllApps + " system=" + includeSystem + " includekeyvalue=" + doKeyValue + " pkgs=" + pkgList);
                Slog.i(TAG, "Beginning adb backup...");
                oldId = oldId2;
                try {
                    AdbBackupParams params = new AdbBackupParams(fd, includeApks, includeObbs, includeShared, doWidgets, doAllApps, includeSystem, compress, doKeyValue, pkgList);
                    int token = generateRandomIntegerToken();
                    synchronized (this.mAdbBackupRestoreConfirmations) {
                        this.mAdbBackupRestoreConfirmations.put(token, params);
                    }
                    Slog.d(TAG, "Starting backup confirmation UI, token=" + token);
                    if (!startConfirmationUi(token, "fullback")) {
                        Slog.e(TAG, "Unable to launch backup confirmation UI");
                        this.mAdbBackupRestoreConfirmations.delete(token);
                        try {
                            fd.close();
                        } catch (IOException e) {
                            Slog.e(TAG, "IO error closing output for adb backup: " + e.getMessage());
                        }
                        Binder.restoreCallingIdentity(oldId);
                        Slog.d(TAG, "Adb backup processing complete.");
                        return;
                    }
                    this.mPowerManager.userActivity(SystemClock.uptimeMillis(), 0, 0);
                    startConfirmationTimeout(token, params);
                    Slog.d(TAG, "Waiting for backup completion...");
                    waitForCompletion(params);
                    try {
                        fd.close();
                    } catch (IOException e2) {
                        Slog.e(TAG, "IO error closing output for adb backup: " + e2.getMessage());
                    }
                    Binder.restoreCallingIdentity(oldId);
                    Slog.d(TAG, "Adb backup processing complete.");
                    return;
                } catch (Throwable th2) {
                    th = th2;
                }
            } else {
                try {
                    Slog.i(TAG, "Backup not supported before setup");
                    try {
                        fd.close();
                    } catch (IOException e3) {
                        Slog.e(TAG, "IO error closing output for adb backup: " + e3.getMessage());
                    }
                    Binder.restoreCallingIdentity(oldId2);
                    Slog.d(TAG, "Adb backup processing complete.");
                    return;
                } catch (Throwable th3) {
                    th = th3;
                    oldId = oldId2;
                }
            }
        } catch (Throwable th4) {
            oldId = oldId2;
            th = th4;
        }
        try {
            fd.close();
        } catch (IOException e4) {
            Slog.e(TAG, "IO error closing output for adb backup: " + e4.getMessage());
        }
        Binder.restoreCallingIdentity(oldId);
        Slog.d(TAG, "Adb backup processing complete.");
        throw th;
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void fullTransportBackup(String[] pkgNames) {
        long oldId;
        this.mContext.enforceCallingPermission("android.permission.BACKUP", "fullTransportBackup");
        int callingUserHandle = UserHandle.getCallingUserId();
        if (callingUserHandle != 0) {
            throw new IllegalStateException("Restore supported only for the device owner");
        }
        String transportName = this.mTransportManager.getCurrentTransportName();
        if (!fullBackupAllowable(transportName)) {
            Slog.i(TAG, "Full backup not currently possible -- key/value backup not yet run?");
        } else {
            Slog.d(TAG, "fullTransportBackup()");
            long oldId2 = Binder.clearCallingIdentity();
            try {
                CountDownLatch latch = new CountDownLatch(1);
                long oldId3 = oldId2;
                try {
                    Runnable task = PerformFullTransportBackupTask.newWithCurrentTransport(this, null, pkgNames, false, null, latch, null, null, false, "BMS.fullTransportBackup()");
                    this.mWakelock.acquire();
                    new Thread(task, "full-transport-master").start();
                    while (true) {
                        try {
                            latch.await();
                            break;
                        } catch (InterruptedException e) {
                            oldId3 = oldId3;
                        }
                    }
                    long now = System.currentTimeMillis();
                    for (String pkg : pkgNames) {
                        try {
                            enqueueFullBackup(pkg, now);
                        } catch (Throwable th) {
                            th = th;
                            oldId = oldId3;
                            Binder.restoreCallingIdentity(oldId);
                            throw th;
                        }
                    }
                    Binder.restoreCallingIdentity(oldId3);
                } catch (Throwable th2) {
                    th = th2;
                    oldId = oldId3;
                }
            } catch (Throwable th3) {
                th = th3;
                oldId = oldId2;
            }
        }
        Slog.d(TAG, "Done with full transport backup.");
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void adbRestore(ParcelFileDescriptor fd) {
        this.mContext.enforceCallingPermission("android.permission.BACKUP", "adbRestore");
        int callingUserHandle = UserHandle.getCallingUserId();
        if (callingUserHandle != 0) {
            throw new IllegalStateException("Restore supported only for the device owner");
        }
        long oldId = Binder.clearCallingIdentity();
        try {
            if (!deviceIsProvisioned()) {
                Slog.i(TAG, "Full restore not permitted before setup");
                try {
                    fd.close();
                } catch (IOException e) {
                    Slog.w(TAG, "Error trying to close fd after adb restore: " + e);
                }
                Binder.restoreCallingIdentity(oldId);
                Slog.i(TAG, "adb restore processing complete.");
                return;
            }
            Slog.i(TAG, "Beginning restore...");
            AdbRestoreParams params = new AdbRestoreParams(fd);
            int token = generateRandomIntegerToken();
            synchronized (this.mAdbBackupRestoreConfirmations) {
                this.mAdbBackupRestoreConfirmations.put(token, params);
            }
            Slog.d(TAG, "Starting restore confirmation UI, token=" + token);
            if (!startConfirmationUi(token, "fullrest")) {
                Slog.e(TAG, "Unable to launch restore confirmation");
                this.mAdbBackupRestoreConfirmations.delete(token);
                try {
                    fd.close();
                } catch (IOException e2) {
                    Slog.w(TAG, "Error trying to close fd after adb restore: " + e2);
                }
                Binder.restoreCallingIdentity(oldId);
                Slog.i(TAG, "adb restore processing complete.");
                return;
            }
            this.mPowerManager.userActivity(SystemClock.uptimeMillis(), 0, 0);
            startConfirmationTimeout(token, params);
            Slog.d(TAG, "Waiting for restore completion...");
            waitForCompletion(params);
            try {
                fd.close();
            } catch (IOException e3) {
                Slog.w(TAG, "Error trying to close fd after adb restore: " + e3);
            }
            Binder.restoreCallingIdentity(oldId);
            Slog.i(TAG, "adb restore processing complete.");
        } catch (Throwable th) {
            try {
                fd.close();
            } catch (IOException e4) {
                Slog.w(TAG, "Error trying to close fd after adb restore: " + e4);
            }
            Binder.restoreCallingIdentity(oldId);
            Slog.i(TAG, "adb restore processing complete.");
            throw th;
        }
    }

    private boolean startConfirmationUi(int token, String action) {
        try {
            Intent confIntent = new Intent(action);
            confIntent.setClassName("com.android.backupconfirm", "com.android.backupconfirm.BackupRestoreConfirmation");
            confIntent.putExtra("conftoken", token);
            confIntent.addFlags(536870912);
            this.mContext.startActivityAsUser(confIntent, UserHandle.SYSTEM);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    private void startConfirmationTimeout(int token, AdbParams params) {
        Message msg = this.mBackupHandler.obtainMessage(9, token, 0, params);
        this.mBackupHandler.sendMessageDelayed(msg, 60000L);
    }

    private void waitForCompletion(AdbParams params) {
        synchronized (params.latch) {
            while (!params.latch.get()) {
                try {
                    params.latch.wait();
                } catch (InterruptedException e) {
                }
            }
        }
    }

    public void signalAdbBackupRestoreCompletion(AdbParams params) {
        synchronized (params.latch) {
            params.latch.set(true);
            params.latch.notifyAll();
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void acknowledgeAdbBackupOrRestore(int token, boolean allow, String curPassword, String encPpassword, IFullBackupRestoreObserver observer) {
        int verb;
        Slog.d(TAG, "acknowledgeAdbBackupOrRestore : token=" + token + " allow=" + allow);
        this.mContext.enforceCallingPermission("android.permission.BACKUP", "acknowledgeAdbBackupOrRestore");
        long oldId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mAdbBackupRestoreConfirmations) {
                AdbParams params = this.mAdbBackupRestoreConfirmations.get(token);
                if (params != null) {
                    this.mBackupHandler.removeMessages(9, params);
                    this.mAdbBackupRestoreConfirmations.delete(token);
                    if (allow) {
                        if (params instanceof AdbBackupParams) {
                            verb = 2;
                        } else {
                            verb = 10;
                        }
                        params.observer = observer;
                        params.curPassword = curPassword;
                        params.encryptPassword = encPpassword;
                        this.mWakelock.acquire();
                        Message msg = this.mBackupHandler.obtainMessage(verb, params);
                        this.mBackupHandler.sendMessage(msg);
                    } else {
                        Slog.w(TAG, "User rejected full backup/restore operation");
                        signalAdbBackupRestoreCompletion(params);
                    }
                } else {
                    Slog.w(TAG, "Attempted to ack full backup/restore with invalid token");
                }
            }
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    private static boolean backupSettingMigrated(int userId) {
        File base = new File(Environment.getDataDirectory(), BatteryService.HealthServiceWrapper.INSTANCE_HEALTHD);
        File enableFile = new File(base, BACKUP_ENABLE_FILE);
        return enableFile.exists();
    }

    private static boolean readBackupEnableState(int userId) {
        File base = new File(Environment.getDataDirectory(), BatteryService.HealthServiceWrapper.INSTANCE_HEALTHD);
        File enableFile = new File(base, BACKUP_ENABLE_FILE);
        if (enableFile.exists()) {
            try {
                FileInputStream fin = new FileInputStream(enableFile);
                int state = fin.read();
                boolean z = state != 0;
                $closeResource(null, fin);
                return z;
            } catch (IOException e) {
                Slog.e(TAG, "Cannot read enable state; assuming disabled");
            }
        } else {
            Slog.i(TAG, "isBackupEnabled() => false due to absent settings file");
        }
        return false;
    }

    private static void writeBackupEnableState(boolean enable, int userId) {
        File base = new File(Environment.getDataDirectory(), BatteryService.HealthServiceWrapper.INSTANCE_HEALTHD);
        File enableFile = new File(base, BACKUP_ENABLE_FILE);
        File stage = new File(base, "backup_enabled-stage");
        try {
            FileOutputStream fout = new FileOutputStream(stage);
            fout.write(enable ? 1 : 0);
            fout.close();
            stage.renameTo(enableFile);
            $closeResource(null, fout);
        } catch (IOException | RuntimeException e) {
            Slog.e(TAG, "Unable to record backup enable state; reverting to disabled: " + e.getMessage());
            ContentResolver r = sInstance.mContext.getContentResolver();
            Settings.Secure.putStringForUser(r, BACKUP_ENABLE_FILE, null, userId);
            enableFile.delete();
            stage.delete();
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void setBackupEnabled(boolean enable) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "setBackupEnabled");
        if (!enable && this.mBackupPolicyEnforcer.getMandatoryBackupTransport() != null) {
            Slog.w(TAG, "Cannot disable backups when the mandatory backups policy is active.");
            return;
        }
        Slog.i(TAG, "Backup enabled => " + enable);
        long oldId = Binder.clearCallingIdentity();
        try {
            boolean wasEnabled = this.mEnabled;
            synchronized (this) {
                writeBackupEnableState(enable, 0);
                this.mEnabled = enable;
            }
            synchronized (this.mQueueLock) {
                if (enable && !wasEnabled && this.mProvisioned) {
                    KeyValueBackupJob.schedule(this.mContext, this.mConstants);
                    scheduleNextFullBackupJob(0L);
                } else if (!enable) {
                    KeyValueBackupJob.cancel(this.mContext);
                    if (wasEnabled && this.mProvisioned) {
                        final List<String> transportNames = new ArrayList<>();
                        final List<String> transportDirNames = new ArrayList<>();
                        this.mTransportManager.forEachRegisteredTransport(new Consumer() { // from class: com.android.server.backup.-$$Lambda$BackupManagerService$Yom7ZUYhsBBc6e92Mh_gepfydaQ
                            @Override // java.util.function.Consumer
                            public final void accept(Object obj) {
                                BackupManagerService.lambda$setBackupEnabled$4(BackupManagerService.this, transportNames, transportDirNames, (String) obj);
                            }
                        });
                        for (int i = 0; i < transportNames.size(); i++) {
                            recordInitPending(true, transportNames.get(i), transportDirNames.get(i));
                        }
                        this.mAlarmManager.set(0, System.currentTimeMillis(), this.mRunInitIntent);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    public static /* synthetic */ void lambda$setBackupEnabled$4(BackupManagerService backupManagerService, List transportNames, List transportDirNames, String name) {
        try {
            String dirName = backupManagerService.mTransportManager.getTransportDirName(name);
            transportNames.add(name);
            transportDirNames.add(dirName);
        } catch (TransportNotRegisteredException e) {
            Slog.e(TAG, "Unexpected unregistered transport", e);
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void setAutoRestore(boolean doAutoRestore) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "setAutoRestore");
        Slog.i(TAG, "Auto restore => " + doAutoRestore);
        long oldId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                Settings.Secure.putInt(this.mContext.getContentResolver(), "backup_auto_restore", doAutoRestore ? 1 : 0);
                this.mAutoRestore = doAutoRestore;
            }
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void setBackupProvisioned(boolean available) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "setBackupProvisioned");
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public boolean isBackupEnabled() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "isBackupEnabled");
        return this.mEnabled;
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public String getCurrentTransport() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "getCurrentTransport");
        String currentTransport = this.mTransportManager.getCurrentTransportName();
        return currentTransport;
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public String[] listAllTransports() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "listAllTransports");
        return this.mTransportManager.getRegisteredTransportNames();
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public ComponentName[] listAllTransportComponents() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "listAllTransportComponents");
        return this.mTransportManager.getRegisteredTransportComponents();
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public String[] getTransportWhitelist() {
        Set<ComponentName> whitelistedComponents = this.mTransportManager.getTransportWhitelist();
        String[] whitelistedTransports = new String[whitelistedComponents.size()];
        int i = 0;
        for (ComponentName component : whitelistedComponents) {
            whitelistedTransports[i] = component.flattenToShortString();
            i++;
        }
        return whitelistedTransports;
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void updateTransportAttributes(ComponentName transportComponent, String name, Intent configurationIntent, String currentDestinationString, Intent dataManagementIntent, String dataManagementLabel) {
        updateTransportAttributes(Binder.getCallingUid(), transportComponent, name, configurationIntent, currentDestinationString, dataManagementIntent, dataManagementLabel);
    }

    @VisibleForTesting
    void updateTransportAttributes(int callingUid, ComponentName transportComponent, String name, Intent configurationIntent, String currentDestinationString, Intent dataManagementIntent, String dataManagementLabel) {
        long oldId;
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "updateTransportAttributes");
        Preconditions.checkNotNull(transportComponent, "transportComponent can't be null");
        Preconditions.checkNotNull(name, "name can't be null");
        Preconditions.checkNotNull(currentDestinationString, "currentDestinationString can't be null");
        Preconditions.checkArgument((dataManagementIntent == null) == (dataManagementLabel == null), "dataManagementLabel should be null iff dataManagementIntent is null");
        try {
            int transportUid = this.mContext.getPackageManager().getPackageUid(transportComponent.getPackageName(), 0);
            if (callingUid != transportUid) {
                try {
                    throw new SecurityException("Only the transport can change its description");
                } catch (PackageManager.NameNotFoundException e) {
                    e = e;
                    throw new SecurityException("Transport package not found", e);
                }
            }
            long oldId2 = Binder.clearCallingIdentity();
            try {
                oldId = oldId2;
                try {
                    this.mTransportManager.updateTransportAttributes(transportComponent, name, configurationIntent, currentDestinationString, dataManagementIntent, dataManagementLabel);
                    Binder.restoreCallingIdentity(oldId);
                } catch (Throwable th) {
                    th = th;
                    Binder.restoreCallingIdentity(oldId);
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                oldId = oldId2;
            }
        } catch (PackageManager.NameNotFoundException e2) {
            e = e2;
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    @Deprecated
    public String selectBackupTransport(String transportName) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "selectBackupTransport");
        if (!isAllowedByMandatoryBackupTransportPolicy(transportName)) {
            Slog.w(TAG, "Failed to select transport - disallowed by device owner policy.");
            return this.mTransportManager.getCurrentTransportName();
        }
        long oldId = Binder.clearCallingIdentity();
        try {
            String previousTransportName = this.mTransportManager.selectTransport(transportName);
            updateStateForTransport(transportName);
            Slog.v(TAG, "selectBackupTransport(transport = " + transportName + "): previous transport = " + previousTransportName);
            return previousTransportName;
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void selectBackupTransportAsync(final ComponentName transportComponent, final ISelectBackupTransportCallback listener) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "selectBackupTransportAsync");
        if (!isAllowedByMandatoryBackupTransportPolicy(transportComponent)) {
            if (listener != null) {
                try {
                    Slog.w(TAG, "Failed to select transport - disallowed by device owner policy.");
                    listener.onFailure(-2001);
                    return;
                } catch (RemoteException e) {
                    Slog.e(TAG, "ISelectBackupTransportCallback listener not available");
                    return;
                }
            }
            return;
        }
        long oldId = Binder.clearCallingIdentity();
        try {
            String transportString = transportComponent.flattenToShortString();
            Slog.v(TAG, "selectBackupTransportAsync(transport = " + transportString + ")");
            this.mBackupHandler.post(new Runnable() { // from class: com.android.server.backup.-$$Lambda$BackupManagerService$DOiHwWNGzZZlYYmgVyeCon2E8lc
                @Override // java.lang.Runnable
                public final void run() {
                    BackupManagerService.lambda$selectBackupTransportAsync$5(BackupManagerService.this, transportComponent, listener);
                }
            });
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    public static /* synthetic */ void lambda$selectBackupTransportAsync$5(BackupManagerService backupManagerService, ComponentName transportComponent, ISelectBackupTransportCallback listener) {
        String transportName = null;
        int result = backupManagerService.mTransportManager.registerAndSelectTransport(transportComponent);
        if (result == 0) {
            try {
                transportName = backupManagerService.mTransportManager.getTransportName(transportComponent);
                backupManagerService.updateStateForTransport(transportName);
            } catch (TransportNotRegisteredException e) {
                Slog.e(TAG, "Transport got unregistered");
                result = -1;
            }
        }
        if (listener != null) {
            try {
                if (transportName != null) {
                    listener.onSuccess(transportName);
                } else {
                    listener.onFailure(result);
                }
            } catch (RemoteException e2) {
                Slog.e(TAG, "ISelectBackupTransportCallback listener not available");
            }
        }
    }

    private boolean isAllowedByMandatoryBackupTransportPolicy(String transportName) {
        ComponentName mandatoryBackupTransport = this.mBackupPolicyEnforcer.getMandatoryBackupTransport();
        if (mandatoryBackupTransport == null) {
            return true;
        }
        try {
            String mandatoryBackupTransportName = this.mTransportManager.getTransportName(mandatoryBackupTransport);
            return TextUtils.equals(mandatoryBackupTransportName, transportName);
        } catch (TransportNotRegisteredException e) {
            Slog.e(TAG, "mandatory backup transport not registered!");
            return false;
        }
    }

    private boolean isAllowedByMandatoryBackupTransportPolicy(ComponentName transport) {
        ComponentName mandatoryBackupTransport = this.mBackupPolicyEnforcer.getMandatoryBackupTransport();
        if (mandatoryBackupTransport == null) {
            return true;
        }
        return mandatoryBackupTransport.equals(transport);
    }

    private void updateStateForTransport(String newTransportName) {
        Settings.Secure.putString(this.mContext.getContentResolver(), "backup_transport", newTransportName);
        TransportClient transportClient = this.mTransportManager.getTransportClient(newTransportName, "BMS.updateStateForTransport()");
        if (transportClient != null) {
            try {
                IBackupTransport transport = transportClient.connectOrThrow("BMS.updateStateForTransport()");
                this.mCurrentToken = transport.getCurrentRestoreSet();
            } catch (Exception e) {
                this.mCurrentToken = 0L;
                Slog.w(TAG, "Transport " + newTransportName + " not available: current token = 0");
            }
            this.mTransportManager.disposeOfTransportClient(transportClient, "BMS.updateStateForTransport()");
            return;
        }
        Slog.w(TAG, "Transport " + newTransportName + " not registered: current token = 0");
        this.mCurrentToken = 0L;
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public Intent getConfigurationIntent(String transportName) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "getConfigurationIntent");
        try {
            Intent intent = this.mTransportManager.getTransportConfigurationIntent(transportName);
            return intent;
        } catch (TransportNotRegisteredException e) {
            Slog.e(TAG, "Unable to get configuration intent from transport: " + e.getMessage());
            return null;
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public String getDestinationString(String transportName) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "getDestinationString");
        try {
            String string = this.mTransportManager.getTransportCurrentDestinationString(transportName);
            return string;
        } catch (TransportNotRegisteredException e) {
            Slog.e(TAG, "Unable to get destination string from transport: " + e.getMessage());
            return null;
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public Intent getDataManagementIntent(String transportName) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "getDataManagementIntent");
        try {
            Intent intent = this.mTransportManager.getTransportDataManagementIntent(transportName);
            return intent;
        } catch (TransportNotRegisteredException e) {
            Slog.e(TAG, "Unable to get management intent from transport: " + e.getMessage());
            return null;
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public String getDataManagementLabel(String transportName) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "getDataManagementLabel");
        try {
            String label = this.mTransportManager.getTransportDataManagementLabel(transportName);
            return label;
        } catch (TransportNotRegisteredException e) {
            Slog.e(TAG, "Unable to get management label from transport: " + e.getMessage());
            return null;
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void agentConnected(String packageName, IBinder agentBinder) {
        synchronized (this.mAgentConnectLock) {
            if (Binder.getCallingUid() == 1000) {
                Slog.d(TAG, "agentConnected pkg=" + packageName + " agent=" + agentBinder);
                IBackupAgent agent = IBackupAgent.Stub.asInterface(agentBinder);
                this.mConnectedAgent = agent;
                this.mConnecting = false;
            } else {
                Slog.w(TAG, "Non-system process uid=" + Binder.getCallingUid() + " claiming agent connected");
            }
            this.mAgentConnectLock.notifyAll();
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void agentDisconnected(String packageName) {
        synchronized (this.mAgentConnectLock) {
            if (Binder.getCallingUid() == 1000) {
                this.mConnectedAgent = null;
                this.mConnecting = false;
            } else {
                Slog.w(TAG, "Non-system process uid=" + Binder.getCallingUid() + " claiming agent disconnected");
            }
            this.mAgentConnectLock.notifyAll();
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void restoreAtInstall(String packageName, int token) {
        if (Binder.getCallingUid() != 1000) {
            Slog.w(TAG, "Non-system process uid=" + Binder.getCallingUid() + " attemping install-time restore");
            return;
        }
        boolean skip = false;
        long restoreSet = getAvailableRestoreToken(packageName);
        Slog.v(TAG, "restoreAtInstall pkg=" + packageName + " token=" + Integer.toHexString(token) + " restoreSet=" + Long.toHexString(restoreSet));
        if (restoreSet == 0) {
            skip = true;
        }
        final TransportClient transportClient = this.mTransportManager.getCurrentTransportClient("BMS.restoreAtInstall()");
        if (transportClient == null) {
            Slog.w(TAG, "No transport client");
            skip = true;
        }
        if (!this.mAutoRestore) {
            Slog.w(TAG, "Non-restorable state: auto=" + this.mAutoRestore);
            skip = true;
        }
        if (!skip) {
            try {
                this.mWakelock.acquire();
                OnTaskFinishedListener listener = new OnTaskFinishedListener() { // from class: com.android.server.backup.-$$Lambda$BackupManagerService$XAHW8jFVbxm2U5esUnLTgJC_Z6Y
                    @Override // com.android.server.backup.internal.OnTaskFinishedListener
                    public final void onFinished(String str) {
                        BackupManagerService.lambda$restoreAtInstall$6(BackupManagerService.this, transportClient, str);
                    }
                };
                Message msg = this.mBackupHandler.obtainMessage(3);
                msg.obj = RestoreParams.createForRestoreAtInstall(transportClient, null, null, restoreSet, packageName, token, listener);
                this.mBackupHandler.sendMessage(msg);
            } catch (Exception e) {
                Slog.e(TAG, "Unable to contact transport: " + e.getMessage());
                skip = true;
            }
        }
        if (skip) {
            if (transportClient != null) {
                this.mTransportManager.disposeOfTransportClient(transportClient, "BMS.restoreAtInstall()");
            }
            Slog.v(TAG, "Finishing install immediately");
            try {
                this.mPackageManagerBinder.finishPackageInstall(token, false);
            } catch (RemoteException e2) {
            }
        }
    }

    public static /* synthetic */ void lambda$restoreAtInstall$6(BackupManagerService backupManagerService, TransportClient transportClient, String caller) {
        backupManagerService.mTransportManager.disposeOfTransportClient(transportClient, caller);
        backupManagerService.mWakelock.release();
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public IRestoreSession beginRestoreSession(String packageName, String transport) {
        Slog.v(TAG, "beginRestoreSession: pkg=" + packageName + " transport=" + transport);
        boolean needPermission = true;
        if (transport == null) {
            transport = this.mTransportManager.getCurrentTransportName();
            if (packageName != null) {
                try {
                    PackageInfo app = this.mPackageManager.getPackageInfo(packageName, 0);
                    if (app.applicationInfo.uid == Binder.getCallingUid()) {
                        needPermission = false;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.w(TAG, "Asked to restore nonexistent pkg " + packageName);
                    throw new IllegalArgumentException("Package " + packageName + " not found");
                }
            }
        }
        if (needPermission) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "beginRestoreSession");
        } else {
            Slog.d(TAG, "restoring self on current transport; no permission needed");
        }
        synchronized (this) {
            if (this.mActiveRestoreSession != null) {
                Slog.i(TAG, "Restore session requested but one already active");
                return null;
            } else if (this.mBackupRunning) {
                Slog.i(TAG, "Restore session requested but currently running backups");
                return null;
            } else {
                this.mActiveRestoreSession = new ActiveRestoreSession(this, packageName, transport);
                this.mBackupHandler.sendEmptyMessageDelayed(8, this.mAgentTimeoutParameters.getRestoreAgentTimeoutMillis());
                return this.mActiveRestoreSession;
            }
        }
    }

    public void clearRestoreSession(ActiveRestoreSession currentSession) {
        synchronized (this) {
            if (currentSession != this.mActiveRestoreSession) {
                Slog.e(TAG, "ending non-current restore session");
            } else {
                Slog.v(TAG, "Clearing restore session and halting timeout");
                this.mActiveRestoreSession = null;
                this.mBackupHandler.removeMessages(8);
            }
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void opComplete(int token, long result) {
        Operation op;
        synchronized (this.mCurrentOpLock) {
            op = this.mCurrentOperations.get(token);
            if (op != null) {
                if (op.state == -1) {
                    op = null;
                    this.mCurrentOperations.delete(token);
                } else if (op.state == 1) {
                    Slog.w(TAG, "Received duplicate ack for token=" + Integer.toHexString(token));
                    op = null;
                    this.mCurrentOperations.remove(token);
                } else if (op.state == 0) {
                    op.state = 1;
                }
            }
            this.mCurrentOpLock.notifyAll();
        }
        if (op != null && op.callback != null) {
            Pair<BackupRestoreTask, Long> callbackAndResult = Pair.create(op.callback, Long.valueOf(result));
            Message msg = this.mBackupHandler.obtainMessage(21, callbackAndResult);
            this.mBackupHandler.sendMessage(msg);
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public boolean isAppEligibleForBackup(String packageName) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "isAppEligibleForBackup");
        long oldToken = Binder.clearCallingIdentity();
        try {
            TransportClient transportClient = this.mTransportManager.getCurrentTransportClient("BMS.isAppEligibleForBackup");
            boolean eligible = AppBackupUtils.appIsRunningAndEligibleForBackupWithTransport(transportClient, packageName, this.mPackageManager);
            if (transportClient != null) {
                this.mTransportManager.disposeOfTransportClient(transportClient, "BMS.isAppEligibleForBackup");
            }
            return eligible;
        } finally {
            Binder.restoreCallingIdentity(oldToken);
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public String[] filterAppsEligibleForBackup(String[] packages) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.BACKUP", "filterAppsEligibleForBackup");
        long oldToken = Binder.clearCallingIdentity();
        try {
            TransportClient transportClient = this.mTransportManager.getCurrentTransportClient("BMS.filterAppsEligibleForBackup");
            List<String> eligibleApps = new LinkedList<>();
            for (String packageName : packages) {
                if (AppBackupUtils.appIsRunningAndEligibleForBackupWithTransport(transportClient, packageName, this.mPackageManager)) {
                    eligibleApps.add(packageName);
                }
            }
            if (transportClient != null) {
                this.mTransportManager.disposeOfTransportClient(transportClient, "BMS.filterAppsEligibleForBackup");
            }
            return (String[]) eligibleApps.toArray(new String[eligibleApps.size()]);
        } finally {
            Binder.restoreCallingIdentity(oldToken);
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpAndUsageStatsPermission(this.mContext, TAG, pw)) {
            long identityToken = Binder.clearCallingIdentity();
            if (args != null) {
                try {
                    for (String arg : args) {
                        if ("-h".equals(arg)) {
                            pw.println("'dumpsys backup' optional arguments:");
                            pw.println("  -h       : this help text");
                            pw.println("  a[gents] : dump information about defined backup agents");
                            return;
                        } else if ("agents".startsWith(arg)) {
                            dumpAgents(pw);
                            return;
                        } else if ("transportclients".equals(arg.toLowerCase())) {
                            this.mTransportManager.dumpTransportClients(pw);
                            return;
                        } else if ("transportstats".equals(arg.toLowerCase())) {
                            this.mTransportManager.dumpTransportStats(pw);
                            return;
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(identityToken);
                }
            }
            dumpInternal(pw);
        }
    }

    private void dumpAgents(PrintWriter pw) {
        List<PackageInfo> agentPackages = allAgentPackages();
        pw.println("Defined backup agents:");
        for (PackageInfo pkg : agentPackages) {
            pw.print("  ");
            pw.print(pkg.packageName);
            pw.println(':');
            pw.print("      ");
            pw.println(pkg.applicationInfo.backupAgentName);
        }
    }

    private void dumpInternal(PrintWriter pw) {
        synchronized (this.mQueueLock) {
            StringBuilder sb = new StringBuilder();
            sb.append("Backup Manager is ");
            sb.append(this.mEnabled ? "enabled" : "disabled");
            sb.append(" / ");
            sb.append(!this.mProvisioned ? "not " : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            sb.append("provisioned / ");
            sb.append(this.mPendingInits.size() == 0 ? "not " : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            sb.append("pending init");
            pw.println(sb.toString());
            StringBuilder sb2 = new StringBuilder();
            sb2.append("Auto-restore is ");
            sb2.append(this.mAutoRestore ? "enabled" : "disabled");
            pw.println(sb2.toString());
            if (this.mBackupRunning) {
                pw.println("Backup currently running");
            }
            pw.println(isBackupOperationInProgress() ? "Backup in progress" : "No backups running");
            pw.println("Last backup pass started: " + this.mLastBackupPass + " (now = " + System.currentTimeMillis() + ')');
            StringBuilder sb3 = new StringBuilder();
            sb3.append("  next scheduled: ");
            sb3.append(KeyValueBackupJob.nextScheduled());
            pw.println(sb3.toString());
            pw.println("Transport whitelist:");
            for (ComponentName transport : this.mTransportManager.getTransportWhitelist()) {
                pw.print("    ");
                pw.println(transport.flattenToShortString());
            }
            pw.println("Available transports:");
            String[] transports = listAllTransports();
            if (transports != null) {
                for (String t : transports) {
                    StringBuilder sb4 = new StringBuilder();
                    sb4.append(t.equals(this.mTransportManager.getCurrentTransportName()) ? "  * " : "    ");
                    sb4.append(t);
                    pw.println(sb4.toString());
                    try {
                        File dir = new File(this.mBaseStateDir, this.mTransportManager.getTransportDirName(t));
                        pw.println("       destination: " + this.mTransportManager.getTransportCurrentDestinationString(t));
                        pw.println("       intent: " + this.mTransportManager.getTransportConfigurationIntent(t));
                        File[] listFiles = dir.listFiles();
                        int length = listFiles.length;
                        for (int i = 0; i < length; i++) {
                            File f = listFiles[i];
                            pw.println("       " + f.getName() + " - " + f.length() + " state bytes");
                        }
                    } catch (Exception e) {
                        Slog.e(TAG, "Error in transport", e);
                        pw.println("        Error: " + e);
                    }
                }
            }
            this.mTransportManager.dumpTransportClients(pw);
            pw.println("Pending init: " + this.mPendingInits.size());
            Iterator<String> it = this.mPendingInits.iterator();
            while (it.hasNext()) {
                String s = it.next();
                pw.println("    " + s);
            }
            synchronized (this.mBackupTrace) {
                if (!this.mBackupTrace.isEmpty()) {
                    pw.println("Most recent backup trace:");
                    for (String s2 : this.mBackupTrace) {
                        pw.println("   " + s2);
                    }
                }
            }
            pw.print("Ancestral: ");
            pw.println(Long.toHexString(this.mAncestralToken));
            pw.print("Current:   ");
            pw.println(Long.toHexString(this.mCurrentToken));
            int N = this.mBackupParticipants.size();
            pw.println("Participants:");
            for (int i2 = 0; i2 < N; i2++) {
                int uid = this.mBackupParticipants.keyAt(i2);
                pw.print("  uid: ");
                pw.println(uid);
                HashSet<String> participants = this.mBackupParticipants.valueAt(i2);
                Iterator<String> it2 = participants.iterator();
                while (it2.hasNext()) {
                    String app = it2.next();
                    pw.println("    " + app);
                }
            }
            StringBuilder sb5 = new StringBuilder();
            sb5.append("Ancestral packages: ");
            sb5.append(this.mAncestralPackages == null ? "none" : Integer.valueOf(this.mAncestralPackages.size()));
            pw.println(sb5.toString());
            if (this.mAncestralPackages != null) {
                for (String pkg : this.mAncestralPackages) {
                    pw.println("    " + pkg);
                }
            }
            Set<String> processedPackages = this.mProcessedPackagesJournal.getPackagesCopy();
            pw.println("Ever backed up: " + processedPackages.size());
            for (String pkg2 : processedPackages) {
                pw.println("    " + pkg2);
            }
            pw.println("Pending key/value backup: " + this.mPendingBackups.size());
            for (BackupRequest req : this.mPendingBackups.values()) {
                pw.println("    " + req);
            }
            pw.println("Full backup queue:" + this.mFullBackupQueue.size());
            Iterator<FullBackupEntry> it3 = this.mFullBackupQueue.iterator();
            while (it3.hasNext()) {
                FullBackupEntry entry = it3.next();
                pw.print("    ");
                pw.print(entry.lastBackup);
                pw.print(" : ");
                pw.println(entry.packageName);
            }
        }
    }

    @Override // com.android.server.backup.BackupManagerServiceInterface
    public IBackupManager getBackupManagerBinder() {
        return this.mBackupManagerBinder;
    }
}
