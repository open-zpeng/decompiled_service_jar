package com.android.server.pm;

import android.content.Context;
import android.content.pm.IOtaDexopt;
import android.content.pm.PackageParser;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.storage.StorageManager;
import android.util.Log;
import android.util.Slog;
import com.android.internal.logging.MetricsLogger;
import com.android.server.pm.Installer;
import com.android.server.pm.PackageDexOptimizer;
import com.android.server.pm.dex.DexoptOptions;
import com.android.server.slice.SliceClientPermissions;
import java.io.File;
import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
/* loaded from: classes.dex */
public class OtaDexoptService extends IOtaDexopt.Stub {
    private static final long BULK_DELETE_THRESHOLD = 1073741824;
    private static final boolean DEBUG_DEXOPT = true;
    private static final String[] NO_LIBRARIES = {PackageDexOptimizer.SKIP_SHARED_LIBRARY_CHECK};
    private static final String TAG = "OTADexopt";
    private long availableSpaceAfterBulkDelete;
    private long availableSpaceAfterDexopt;
    private long availableSpaceBefore;
    private int completeSize;
    private int dexoptCommandCountExecuted;
    private int dexoptCommandCountTotal;
    private int importantPackageCount;
    private final Context mContext;
    private List<String> mDexoptCommands;
    private final PackageManagerService mPackageManagerService;
    private long otaDexoptTimeStart;
    private int otherPackageCount;

    public OtaDexoptService(Context context, PackageManagerService packageManagerService) {
        this.mContext = context;
        this.mPackageManagerService = packageManagerService;
    }

    /* JADX WARN: Type inference failed for: r0v0, types: [com.android.server.pm.OtaDexoptService, android.os.IBinder] */
    public static OtaDexoptService main(Context context, PackageManagerService packageManagerService) {
        ?? otaDexoptService = new OtaDexoptService(context, packageManagerService);
        ServiceManager.addService("otadexopt", (IBinder) otaDexoptService);
        otaDexoptService.moveAbArtifacts(packageManagerService.mInstaller);
        return otaDexoptService;
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new OtaDexoptShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    public synchronized void prepare() throws RemoteException {
        List<PackageParser.Package> important;
        List<PackageParser.Package> others;
        if (this.mDexoptCommands != null) {
            throw new IllegalStateException("already called prepare()");
        }
        synchronized (this.mPackageManagerService.mPackages) {
            important = PackageManagerServiceUtils.getPackagesForDexopt(this.mPackageManagerService.mPackages.values(), this.mPackageManagerService);
            others = new ArrayList<>((Collection<? extends PackageParser.Package>) this.mPackageManagerService.mPackages.values());
            others.removeAll(important);
            this.mDexoptCommands = new ArrayList((3 * this.mPackageManagerService.mPackages.size()) / 2);
        }
        for (PackageParser.Package p : important) {
            this.mDexoptCommands.addAll(generatePackageDexopts(p, 4));
        }
        for (PackageParser.Package p2 : others) {
            if (p2.coreApp) {
                throw new IllegalStateException("Found a core app that's not important");
            }
            this.mDexoptCommands.addAll(generatePackageDexopts(p2, 0));
        }
        this.completeSize = this.mDexoptCommands.size();
        long spaceAvailable = getAvailableSpace();
        if (spaceAvailable < BULK_DELETE_THRESHOLD) {
            Log.i(TAG, "Low on space, deleting oat files in an attempt to free up space: " + PackageManagerServiceUtils.packagesToString(others));
            for (PackageParser.Package pkg : others) {
                this.mPackageManagerService.deleteOatArtifactsOfPackage(pkg.packageName);
            }
        }
        long spaceAvailableNow = getAvailableSpace();
        prepareMetricsLogging(important.size(), others.size(), spaceAvailable, spaceAvailableNow);
    }

    public synchronized void cleanup() throws RemoteException {
        Log.i(TAG, "Cleaning up OTA Dexopt state.");
        this.mDexoptCommands = null;
        this.availableSpaceAfterDexopt = getAvailableSpace();
        performMetricsLogging();
    }

    public synchronized boolean isDone() throws RemoteException {
        if (this.mDexoptCommands == null) {
            throw new IllegalStateException("done() called before prepare()");
        }
        return this.mDexoptCommands.isEmpty();
    }

    public synchronized float getProgress() throws RemoteException {
        if (this.completeSize == 0) {
            return 1.0f;
        }
        int commandsLeft = this.mDexoptCommands.size();
        return (this.completeSize - commandsLeft) / this.completeSize;
    }

    public synchronized String nextDexoptCommand() throws RemoteException {
        if (this.mDexoptCommands == null) {
            throw new IllegalStateException("dexoptNextPackage() called before prepare()");
        }
        if (this.mDexoptCommands.isEmpty()) {
            return "(all done)";
        }
        String next = this.mDexoptCommands.remove(0);
        if (getAvailableSpace() > 0) {
            this.dexoptCommandCountExecuted++;
            Log.d(TAG, "Next command: " + next);
            return next;
        }
        Log.w(TAG, "Not enough space for OTA dexopt, stopping with " + (this.mDexoptCommands.size() + 1) + " commands left.");
        this.mDexoptCommands.clear();
        return "(no free space)";
    }

    private long getMainLowSpaceThreshold() {
        File dataDir = Environment.getDataDirectory();
        long lowThreshold = StorageManager.from(this.mContext).getStorageLowBytes(dataDir);
        if (lowThreshold == 0) {
            throw new IllegalStateException("Invalid low memory threshold");
        }
        return lowThreshold;
    }

    private long getAvailableSpace() {
        long lowThreshold = getMainLowSpaceThreshold();
        File dataDir = Environment.getDataDirectory();
        long usableSpace = dataDir.getUsableSpace();
        return usableSpace - lowThreshold;
    }

    private synchronized List<String> generatePackageDexopts(PackageParser.Package pkg, int compilationReason) {
        final List<String> commands;
        commands = new ArrayList<>();
        Installer collectingInstaller = new Installer(this.mContext, true) { // from class: com.android.server.pm.OtaDexoptService.1
            @Override // com.android.server.pm.Installer
            public void dexopt(String apkPath, int uid, String pkgName, String instructionSet, int dexoptNeeded, String outputPath, int dexFlags, String compilerFilter, String volumeUuid, String sharedLibraries, String seInfo, boolean downgrade, int targetSdkVersion, String profileName, String dexMetadataPath, String dexoptCompilationReason) throws Installer.InstallerException {
                StringBuilder builder = new StringBuilder();
                builder.append("9 ");
                builder.append("dexopt");
                encodeParameter(builder, apkPath);
                encodeParameter(builder, Integer.valueOf(uid));
                encodeParameter(builder, pkgName);
                encodeParameter(builder, instructionSet);
                encodeParameter(builder, Integer.valueOf(dexoptNeeded));
                encodeParameter(builder, outputPath);
                encodeParameter(builder, Integer.valueOf(dexFlags));
                encodeParameter(builder, compilerFilter);
                encodeParameter(builder, volumeUuid);
                encodeParameter(builder, sharedLibraries);
                encodeParameter(builder, seInfo);
                encodeParameter(builder, Boolean.valueOf(downgrade));
                encodeParameter(builder, Integer.valueOf(targetSdkVersion));
                encodeParameter(builder, profileName);
                encodeParameter(builder, dexMetadataPath);
                encodeParameter(builder, dexoptCompilationReason);
                commands.add(builder.toString());
            }

            private void encodeParameter(StringBuilder builder, Object arg) {
                builder.append(' ');
                if (arg == null) {
                    builder.append('!');
                    return;
                }
                String txt = String.valueOf(arg);
                if (txt.indexOf(0) != -1 || txt.indexOf(32) != -1 || "!".equals(txt)) {
                    throw new IllegalArgumentException("Invalid argument while executing " + arg);
                }
                builder.append(txt);
            }
        };
        PackageDexOptimizer optimizer = new OTADexoptPackageDexOptimizer(collectingInstaller, this.mPackageManagerService.mInstallLock, this.mContext);
        String[] libraryDependencies = pkg.usesLibraryFiles;
        if (pkg.isSystem()) {
            libraryDependencies = NO_LIBRARIES;
        }
        optimizer.performDexOpt(pkg, libraryDependencies, null, null, this.mPackageManagerService.getDexManager().getPackageUseInfoOrDefault(pkg.packageName), new DexoptOptions(pkg.packageName, compilationReason, 4));
        return commands;
    }

    public synchronized void dexoptNextPackage() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    private void moveAbArtifacts(Installer installer) {
        if (this.mDexoptCommands != null) {
            throw new IllegalStateException("Should not be ota-dexopting when trying to move.");
        }
        if (!this.mPackageManagerService.isUpgrade()) {
            Slog.d(TAG, "No upgrade, skipping A/B artifacts check.");
            return;
        }
        Collection<PackageParser.Package> pkgs = this.mPackageManagerService.getPackages();
        int packagePaths = 0;
        int pathsSuccessful = 0;
        for (PackageParser.Package pkg : pkgs) {
            if (pkg != null && PackageDexOptimizer.canOptimizePackage(pkg)) {
                if (pkg.codePath == null) {
                    Slog.w(TAG, "Package " + pkg + " can be optimized but has null codePath");
                } else if (!pkg.codePath.startsWith("/system") && !pkg.codePath.startsWith("/vendor") && !pkg.codePath.startsWith("/product")) {
                    String[] instructionSets = InstructionSets.getAppDexInstructionSets(pkg.applicationInfo);
                    List<String> paths = pkg.getAllCodePathsExcludingResourceOnly();
                    String[] dexCodeInstructionSets = InstructionSets.getDexCodeInstructionSets(instructionSets);
                    for (String dexCodeInstructionSet : dexCodeInstructionSets) {
                        for (String path : paths) {
                            String oatDir = PackageDexOptimizer.getOatDir(new File(pkg.codePath)).getAbsolutePath();
                            int packagePaths2 = packagePaths + 1;
                            try {
                                installer.moveAb(path, dexCodeInstructionSet, oatDir);
                                pathsSuccessful++;
                            } catch (Installer.InstallerException e) {
                            }
                            packagePaths = packagePaths2;
                        }
                    }
                }
            }
        }
        Slog.i(TAG, "Moved " + pathsSuccessful + SliceClientPermissions.SliceAuthority.DELIMITER + packagePaths);
    }

    private void prepareMetricsLogging(int important, int others, long spaceBegin, long spaceBulk) {
        this.availableSpaceBefore = spaceBegin;
        this.availableSpaceAfterBulkDelete = spaceBulk;
        this.availableSpaceAfterDexopt = 0L;
        this.importantPackageCount = important;
        this.otherPackageCount = others;
        this.dexoptCommandCountTotal = this.mDexoptCommands.size();
        this.dexoptCommandCountExecuted = 0;
        this.otaDexoptTimeStart = System.nanoTime();
    }

    private static int inMegabytes(long value) {
        long in_mega_bytes = value / 1048576;
        if (in_mega_bytes > 2147483647L) {
            Log.w(TAG, "Recording " + in_mega_bytes + "MB of free space, overflowing range");
            return Integer.MAX_VALUE;
        }
        return (int) in_mega_bytes;
    }

    private void performMetricsLogging() {
        long finalTime = System.nanoTime();
        MetricsLogger.histogram(this.mContext, "ota_dexopt_available_space_before_mb", inMegabytes(this.availableSpaceBefore));
        MetricsLogger.histogram(this.mContext, "ota_dexopt_available_space_after_bulk_delete_mb", inMegabytes(this.availableSpaceAfterBulkDelete));
        MetricsLogger.histogram(this.mContext, "ota_dexopt_available_space_after_dexopt_mb", inMegabytes(this.availableSpaceAfterDexopt));
        MetricsLogger.histogram(this.mContext, "ota_dexopt_num_important_packages", this.importantPackageCount);
        MetricsLogger.histogram(this.mContext, "ota_dexopt_num_other_packages", this.otherPackageCount);
        MetricsLogger.histogram(this.mContext, "ota_dexopt_num_commands", this.dexoptCommandCountTotal);
        MetricsLogger.histogram(this.mContext, "ota_dexopt_num_commands_executed", this.dexoptCommandCountExecuted);
        int elapsedTimeSeconds = (int) TimeUnit.NANOSECONDS.toSeconds(finalTime - this.otaDexoptTimeStart);
        MetricsLogger.histogram(this.mContext, "ota_dexopt_time_s", elapsedTimeSeconds);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class OTADexoptPackageDexOptimizer extends PackageDexOptimizer.ForcedUpdatePackageDexOptimizer {
        public OTADexoptPackageDexOptimizer(Installer installer, Object installLock, Context context) {
            super(installer, installLock, context, "*otadexopt*");
        }
    }
}
