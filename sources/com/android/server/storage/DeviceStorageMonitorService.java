package com.android.server.storage;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.util.ArrayMap;
import android.util.DataUnit;
import android.util.Slog;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.EventLogTags;
import com.android.server.SystemService;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.InstructionSets;
import com.android.server.pm.PackageManagerService;
import com.android.server.utils.PriorityDump;
import com.xiaopeng.server.aftersales.AfterSalesService;
import dalvik.system.VMRuntime;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
/* loaded from: classes.dex */
public class DeviceStorageMonitorService extends SystemService {
    private static final long DEFAULT_CHECK_INTERVAL = 60000;
    public static final String EXTRA_SEQUENCE = "seq";
    private static final int MSG_CHECK = 1;
    private static final int NEW_STORAGE_LOW_PERCENTAGE = 10;
    static final int OPTION_FORCE_UPDATE = 1;
    static final String SERVICE = "devicestoragemonitor";
    private static final String STORAGE_LOW_INTENT = "com.xiaopeng.intent.action.ACTION_DEVICE_STORAGE_LOW";
    private static final String STORAGE_OK_INTENT = "com.xiaopeng.intent.action.ACTION_DEVICE_STORAGE_OK";
    private static final String TAG = "DeviceStorageMonitorService";
    private static final String TV_NOTIFICATION_CHANNEL_ID = "devicestoragemonitor.tv";
    private CacheFileDeletedObserver mCacheFileDeletedObserver;
    private volatile int mForceLevel;
    private final Handler mHandler;
    private final HandlerThread mHandlerThread;
    private final DeviceStorageMonitorInternal mLocalService;
    private NotificationManager mNotifManager;
    private final Binder mRemoteService;
    private final AtomicInteger mSeq;
    private final ArrayMap<UUID, State> mStates;
    private static boolean NEW_DEFAULT_NOT_LOW = true;
    private static final long DEFAULT_LOG_DELTA_BYTES = DataUnit.MEBIBYTES.toBytes(64);
    private static final long BOOT_IMAGE_STORAGE_REQUIREMENT = DataUnit.MEBIBYTES.toBytes(250);

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class State {
        private static final int LEVEL_FULL = 2;
        private static final int LEVEL_LOW = 1;
        private static final int LEVEL_NORMAL = 0;
        private static final int LEVEL_UNKNOWN = -1;
        public long lastUsableBytes;
        public int level;

        private State() {
            this.level = 0;
            this.lastUsableBytes = JobStatus.NO_LATEST_RUNTIME;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public static boolean isEntering(int level, int oldLevel, int newLevel) {
            return newLevel >= level && (oldLevel < level || oldLevel == -1);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public static boolean isLeaving(int level, int oldLevel, int newLevel) {
            return newLevel < level && (oldLevel >= level || oldLevel == -1);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public static String levelToString(int level) {
            switch (level) {
                case -1:
                    return "UNKNOWN";
                case 0:
                    return PriorityDump.PRIORITY_ARG_NORMAL;
                case 1:
                    return "LOW";
                case 2:
                    return "FULL";
                default:
                    return Integer.toString(level);
            }
        }
    }

    private State findOrCreateState(UUID uuid) {
        State state = this.mStates.get(uuid);
        if (state == null) {
            State state2 = new State();
            this.mStates.put(uuid, state2);
            return state2;
        }
        return state;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void check() {
        int oldLevel;
        int newLevel;
        StorageManager storage = (StorageManager) getContext().getSystemService(StorageManager.class);
        int seq = this.mSeq.get();
        if (storage == null) {
            return;
        }
        for (VolumeInfo vol : storage.getWritablePrivateVolumes()) {
            File file = vol.getPath();
            long fullBytes = storage.getStorageFullBytes(file);
            long lowBytes = storage.getStorageLowBytes(file);
            if (file.getUsableSpace() < (3 * lowBytes) / 2) {
                PackageManagerService pms = (PackageManagerService) ServiceManager.getService("package");
                try {
                    pms.freeStorage(vol.getFsUuid(), lowBytes * 2, 0);
                } catch (IOException e) {
                    Slog.w(TAG, e);
                }
            }
            UUID uuid = StorageManager.convert(vol.getFsUuid());
            State state = findOrCreateState(uuid);
            long totalBytes = file.getTotalSpace();
            long usableBytes = file.getUsableSpace();
            int oldLevel2 = state.level;
            StorageManager storage2 = storage;
            if (this.mForceLevel != -1) {
                oldLevel = -1;
                newLevel = this.mForceLevel;
            } else {
                if (usableBytes <= fullBytes) {
                    newLevel = 2;
                } else if (usableBytes <= lowBytes) {
                    newLevel = 1;
                } else if (StorageManager.UUID_DEFAULT.equals(uuid) && !isBootImageOnDisk() && usableBytes < BOOT_IMAGE_STORAGE_REQUIREMENT) {
                    newLevel = 1;
                } else {
                    oldLevel = oldLevel2;
                    newLevel = 0;
                }
                oldLevel = oldLevel2;
            }
            if (Math.abs(state.lastUsableBytes - usableBytes) > DEFAULT_LOG_DELTA_BYTES || oldLevel != newLevel) {
                EventLogTags.writeStorageState(uuid.toString(), oldLevel, newLevel, usableBytes, totalBytes);
                state.lastUsableBytes = usableBytes;
            }
            updateNotifications(vol, oldLevel, newLevel);
            updateBroadcasts(vol, oldLevel, newLevel, seq);
            state.level = newLevel;
            storage = storage2;
        }
        if (!this.mHandler.hasMessages(1)) {
            this.mHandler.sendMessageDelayed(this.mHandler.obtainMessage(1), 60000L);
        }
    }

    public DeviceStorageMonitorService(Context context) {
        super(context);
        this.mSeq = new AtomicInteger(1);
        this.mForceLevel = -1;
        this.mStates = new ArrayMap<>();
        this.mLocalService = new DeviceStorageMonitorInternal() { // from class: com.android.server.storage.DeviceStorageMonitorService.2
            @Override // com.android.server.storage.DeviceStorageMonitorInternal
            public void checkMemory() {
                DeviceStorageMonitorService.this.mHandler.removeMessages(1);
                DeviceStorageMonitorService.this.mHandler.obtainMessage(1).sendToTarget();
            }

            @Override // com.android.server.storage.DeviceStorageMonitorInternal
            public boolean isMemoryLow() {
                return Environment.getDataDirectory().getUsableSpace() < getMemoryLowThreshold();
            }

            @Override // com.android.server.storage.DeviceStorageMonitorInternal
            public long getMemoryLowThreshold() {
                return ((StorageManager) DeviceStorageMonitorService.this.getContext().getSystemService(StorageManager.class)).getStorageLowBytes(Environment.getDataDirectory());
            }
        };
        this.mRemoteService = new Binder() { // from class: com.android.server.storage.DeviceStorageMonitorService.3
            @Override // android.os.Binder
            protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
                if (DumpUtils.checkDumpPermission(DeviceStorageMonitorService.this.getContext(), DeviceStorageMonitorService.TAG, pw)) {
                    DeviceStorageMonitorService.this.dumpImpl(fd, pw, args);
                }
            }

            public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
                new Shell().exec(this, in, out, err, args, callback, resultReceiver);
            }
        };
        this.mHandlerThread = new HandlerThread(TAG, 10);
        this.mHandlerThread.start();
        this.mHandler = new Handler(this.mHandlerThread.getLooper()) { // from class: com.android.server.storage.DeviceStorageMonitorService.1
            @Override // android.os.Handler
            public void handleMessage(Message msg) {
                if (msg.what == 1) {
                    DeviceStorageMonitorService.this.check();
                }
            }
        };
    }

    private static boolean isBootImageOnDisk() {
        String[] allDexCodeInstructionSets;
        for (String instructionSet : InstructionSets.getAllDexCodeInstructionSets()) {
            if (!VMRuntime.isBootClassPathOnDisk(instructionSet)) {
                return false;
            }
        }
        return true;
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        Context context = getContext();
        this.mNotifManager = (NotificationManager) context.getSystemService(NotificationManager.class);
        this.mCacheFileDeletedObserver = new CacheFileDeletedObserver();
        this.mCacheFileDeletedObserver.startWatching();
        PackageManager packageManager = context.getPackageManager();
        boolean isTv = packageManager.hasSystemFeature("android.software.leanback");
        if (isTv) {
            this.mNotifManager.createNotificationChannel(new NotificationChannel(TV_NOTIFICATION_CHANNEL_ID, context.getString(17039808), 4));
        }
        publishBinderService(SERVICE, this.mRemoteService);
        publishLocalService(DeviceStorageMonitorInternal.class, this.mLocalService);
        this.mHandler.removeMessages(1);
        this.mHandler.obtainMessage(1).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class Shell extends ShellCommand {
        Shell() {
        }

        public int onCommand(String cmd) {
            return DeviceStorageMonitorService.this.onShellCommand(this, cmd);
        }

        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            DeviceStorageMonitorService.dumpHelp(pw);
        }
    }

    int parseOptions(Shell shell) {
        int opts = 0;
        while (true) {
            String opt = shell.getNextOption();
            if (opt != null) {
                if ("-f".equals(opt)) {
                    opts |= 1;
                }
            } else {
                return opts;
            }
        }
    }

    int onShellCommand(Shell shell, String cmd) {
        char c;
        if (cmd == null) {
            return shell.handleDefaultCommands(cmd);
        }
        PrintWriter pw = shell.getOutPrintWriter();
        int hashCode = cmd.hashCode();
        if (hashCode == 108404047) {
            if (cmd.equals("reset")) {
                c = 2;
            }
            c = 65535;
        } else if (hashCode != 1526871410) {
            if (hashCode == 1692300408 && cmd.equals("force-not-low")) {
                c = 1;
            }
            c = 65535;
        } else {
            if (cmd.equals("force-low")) {
                c = 0;
            }
            c = 65535;
        }
        switch (c) {
            case 0:
                int opts = parseOptions(shell);
                getContext().enforceCallingOrSelfPermission("android.permission.DEVICE_POWER", null);
                this.mForceLevel = 1;
                int seq = this.mSeq.incrementAndGet();
                if ((opts & 1) != 0) {
                    this.mHandler.removeMessages(1);
                    this.mHandler.obtainMessage(1).sendToTarget();
                    pw.println(seq);
                    break;
                }
                break;
            case 1:
                int opts2 = parseOptions(shell);
                getContext().enforceCallingOrSelfPermission("android.permission.DEVICE_POWER", null);
                this.mForceLevel = 0;
                int seq2 = this.mSeq.incrementAndGet();
                if ((opts2 & 1) != 0) {
                    this.mHandler.removeMessages(1);
                    this.mHandler.obtainMessage(1).sendToTarget();
                    pw.println(seq2);
                    break;
                }
                break;
            case 2:
                int opts3 = parseOptions(shell);
                getContext().enforceCallingOrSelfPermission("android.permission.DEVICE_POWER", null);
                this.mForceLevel = -1;
                int seq3 = this.mSeq.incrementAndGet();
                if ((opts3 & 1) != 0) {
                    this.mHandler.removeMessages(1);
                    this.mHandler.obtainMessage(1).sendToTarget();
                    pw.println(seq3);
                    break;
                }
                break;
            default:
                return shell.handleDefaultCommands(cmd);
        }
        return 0;
    }

    static void dumpHelp(PrintWriter pw) {
        pw.println("Device storage monitor service (devicestoragemonitor) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  force-low [-f]");
        pw.println("    Force storage to be low, freezing storage state.");
        pw.println("    -f: force a storage change broadcast be sent, prints new sequence.");
        pw.println("  force-not-low [-f]");
        pw.println("    Force storage to not be low, freezing storage state.");
        pw.println("    -f: force a storage change broadcast be sent, prints new sequence.");
        pw.println("  reset [-f]");
        pw.println("    Unfreeze storage state, returning to current real values.");
        pw.println("    -f: force a storage change broadcast be sent, prints new sequence.");
    }

    void dumpImpl(FileDescriptor fd, PrintWriter _pw, String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(_pw, "  ");
        if (args == null || args.length == 0 || "-a".equals(args[0])) {
            pw.println("Known volumes:");
            pw.increaseIndent();
            for (int i = 0; i < this.mStates.size(); i++) {
                UUID uuid = this.mStates.keyAt(i);
                State state = this.mStates.valueAt(i);
                if (StorageManager.UUID_DEFAULT.equals(uuid)) {
                    pw.println("Default:");
                } else {
                    pw.println(uuid + ":");
                }
                pw.increaseIndent();
                pw.printPair("level", State.levelToString(state.level));
                pw.printPair("lastUsableBytes", Long.valueOf(state.lastUsableBytes));
                pw.println();
                pw.decreaseIndent();
            }
            pw.decreaseIndent();
            pw.println();
            pw.printPair("mSeq", Integer.valueOf(this.mSeq.get()));
            pw.printPair("mForceState", State.levelToString(this.mForceLevel));
            pw.println();
            pw.println();
            return;
        }
        Shell shell = new Shell();
        shell.exec(this.mRemoteService, null, fd, null, args, null, new ResultReceiver(null));
    }

    private void updateNotifications(VolumeInfo vol, int oldLevel, int newLevel) {
        CharSequence details;
        Context context = getContext();
        UUID uuid = StorageManager.convert(vol.getFsUuid());
        if (!State.isEntering(1, oldLevel, newLevel)) {
            if (State.isLeaving(1, oldLevel, newLevel)) {
                this.mNotifManager.cancelAsUser(uuid.toString(), 23, UserHandle.ALL);
                return;
            }
            return;
        }
        Intent lowMemIntent = new Intent("android.os.storage.action.MANAGE_STORAGE");
        lowMemIntent.putExtra("android.os.storage.extra.UUID", uuid);
        lowMemIntent.addFlags(268435456);
        CharSequence title = context.getText(17040205);
        if (StorageManager.UUID_DEFAULT.equals(uuid)) {
            details = context.getText(isBootImageOnDisk() ? 17040203 : 17040204);
        } else {
            details = context.getText(17040203);
        }
        CharSequence details2 = details;
        PendingIntent intent = PendingIntent.getActivityAsUser(context, 0, lowMemIntent, 0, null, UserHandle.CURRENT);
        Notification notification = new Notification.Builder(context, SystemNotificationChannels.ALERTS).setSmallIcon(17303450).setTicker(title).setColor(context.getColor(17170861)).setContentTitle(title).setContentText(details2).setContentIntent(intent).setStyle(new Notification.BigTextStyle().bigText(details2)).setVisibility(1).setCategory("sys").extend(new Notification.TvExtender().setChannelId(TV_NOTIFICATION_CHANNEL_ID)).build();
        notification.flags |= 32;
        this.mNotifManager.notifyAsUser(uuid.toString(), 23, notification, UserHandle.ALL);
    }

    private void updateBroadcasts(VolumeInfo vol, int oldLevel, int newLevel, int seq) {
        if (!Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL, vol.getFsUuid())) {
            return;
        }
        Intent lowIntent = new Intent("android.intent.action.DEVICE_STORAGE_LOW").addFlags(85983232).putExtra(EXTRA_SEQUENCE, seq);
        Intent notLowIntent = new Intent("android.intent.action.DEVICE_STORAGE_OK").addFlags(85983232).putExtra(EXTRA_SEQUENCE, seq);
        if (!State.isEntering(1, oldLevel, newLevel)) {
            if (State.isLeaving(1, oldLevel, newLevel)) {
                getContext().removeStickyBroadcastAsUser(lowIntent, UserHandle.ALL);
                getContext().sendBroadcastAsUser(notLowIntent, UserHandle.ALL);
            }
        } else {
            getContext().sendStickyBroadcastAsUser(lowIntent, UserHandle.ALL);
        }
        Intent fullIntent = new Intent("android.intent.action.DEVICE_STORAGE_FULL").addFlags(67108864).putExtra(EXTRA_SEQUENCE, seq);
        Intent notFullIntent = new Intent("android.intent.action.DEVICE_STORAGE_NOT_FULL").addFlags(67108864).putExtra(EXTRA_SEQUENCE, seq);
        if (!State.isEntering(2, oldLevel, newLevel)) {
            if (State.isLeaving(2, oldLevel, newLevel)) {
                getContext().removeStickyBroadcastAsUser(fullIntent, UserHandle.ALL);
                getContext().sendBroadcastAsUser(notFullIntent, UserHandle.ALL);
            }
        } else {
            getContext().sendStickyBroadcastAsUser(fullIntent, UserHandle.ALL);
        }
        File file = vol.getPath();
        if (file.getUsableSpace() < (file.getTotalSpace() * 10) / 100) {
            if (NEW_DEFAULT_NOT_LOW) {
                Slog.d(TAG, "send storage low");
                Intent intent = new Intent(STORAGE_LOW_INTENT);
                intent.addFlags(83886080);
                getContext().sendBroadcastAsUser(intent, UserHandle.ALL);
                NEW_DEFAULT_NOT_LOW = false;
                Intent intentServer = new Intent("com.xiaopeng.cardiagnosis.service.StorageClearService");
                intentServer.setPackage(AfterSalesService.PackgeName.CARDIAGNOSIS);
                intentServer.putExtra("isFirstStart", false);
                getContext().startServiceAsUser(intentServer, UserHandle.SYSTEM);
            }
        } else if (!NEW_DEFAULT_NOT_LOW) {
            Slog.d(TAG, "send storage ok");
            Intent intent2 = new Intent(STORAGE_OK_INTENT);
            intent2.addFlags(83886080);
            getContext().sendBroadcastAsUser(intent2, UserHandle.ALL);
            NEW_DEFAULT_NOT_LOW = true;
        }
    }

    /* loaded from: classes.dex */
    private static class CacheFileDeletedObserver extends FileObserver {
        public CacheFileDeletedObserver() {
            super(Environment.getDownloadCacheDirectory().getAbsolutePath(), 512);
        }

        @Override // android.os.FileObserver
        public void onEvent(int event, String path) {
            EventLogTags.writeCacheFileDeleted(path);
        }
    }
}
