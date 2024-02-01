package com.android.server.pm;

import android.app.AppGlobals;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageParser;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.hardware.biometrics.fingerprint.V2_1.RequestStatus;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.FileUtils;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import com.android.internal.content.PackageHelper;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.server.EventLogTags;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.PackageDexUsage;
import dalvik.system.VMRuntime;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import libcore.io.IoUtils;

/* loaded from: classes.dex */
public class PackageManagerServiceUtils {
    private static final int FSVERITY_DISABLED = 0;
    private static final int FSVERITY_ENABLED = 2;
    private static final int FSVERITY_LEGACY = 1;
    private static final long SEVEN_DAYS_IN_MILLISECONDS = 604800000;

    private static ArraySet<String> getPackageNamesForIntent(Intent intent, int userId) {
        List<ResolveInfo> ris = null;
        try {
            ris = AppGlobals.getPackageManager().queryIntentReceivers(intent, (String) null, 0, userId).getList();
        } catch (RemoteException e) {
        }
        ArraySet<String> pkgNames = new ArraySet<>();
        if (ris != null) {
            for (ResolveInfo ri : ris) {
                pkgNames.add(ri.activityInfo.packageName);
            }
        }
        return pkgNames;
    }

    public static void sortPackagesByUsageDate(List<PackageParser.Package> pkgs, PackageManagerService packageManagerService) {
        if (!packageManagerService.isHistoricalPackageUsageAvailable()) {
            return;
        }
        Collections.sort(pkgs, new Comparator() { // from class: com.android.server.pm.-$$Lambda$PackageManagerServiceUtils$ePZ6rsJ05hJ2glmOqcq1_jX6J8w
            @Override // java.util.Comparator
            public final int compare(Object obj, Object obj2) {
                int compare;
                compare = Long.compare(((PackageParser.Package) obj2).getLatestForegroundPackageUseTimeInMills(), ((PackageParser.Package) obj).getLatestForegroundPackageUseTimeInMills());
                return compare;
            }
        });
    }

    private static void applyPackageFilter(Predicate<PackageParser.Package> filter, Collection<PackageParser.Package> result, Collection<PackageParser.Package> packages, List<PackageParser.Package> sortTemp, PackageManagerService packageManagerService) {
        for (PackageParser.Package pkg : packages) {
            if (filter.test(pkg)) {
                sortTemp.add(pkg);
            }
        }
        sortPackagesByUsageDate(sortTemp, packageManagerService);
        packages.removeAll(sortTemp);
        for (PackageParser.Package pkg2 : sortTemp) {
            result.add(pkg2);
            Collection<PackageParser.Package> deps = packageManagerService.findSharedNonSystemLibraries(pkg2);
            if (!deps.isEmpty()) {
                deps.removeAll(result);
                result.addAll(deps);
                packages.removeAll(deps);
            }
        }
        sortTemp.clear();
    }

    public static List<PackageParser.Package> getPackagesForDexopt(Collection<PackageParser.Package> packages, PackageManagerService packageManagerService) {
        return getPackagesForDexopt(packages, packageManagerService, false);
    }

    public static List<PackageParser.Package> getPackagesForDexopt(Collection<PackageParser.Package> packages, PackageManagerService packageManagerService, boolean debug) {
        Predicate<PackageParser.Package> remainingPredicate;
        ArrayList<PackageParser.Package> remainingPkgs = new ArrayList<>((Collection<? extends PackageParser.Package>) packages);
        LinkedList<PackageParser.Package> result = new LinkedList<>();
        ArrayList<PackageParser.Package> sortTemp = new ArrayList<>(remainingPkgs.size());
        applyPackageFilter(new Predicate() { // from class: com.android.server.pm.-$$Lambda$PackageManagerServiceUtils$QMV-UHbRIK26QMZL5iM27MchX7U
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean z;
                z = ((PackageParser.Package) obj).coreApp;
                return z;
            }
        }, result, remainingPkgs, sortTemp, packageManagerService);
        Intent intent = new Intent("android.intent.action.PRE_BOOT_COMPLETED");
        final ArraySet<String> pkgNames = getPackageNamesForIntent(intent, 0);
        applyPackageFilter(new Predicate() { // from class: com.android.server.pm.-$$Lambda$PackageManagerServiceUtils$nPt0Hym3GvYeWA2vwfOLFDxZmCE
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean contains;
                contains = pkgNames.contains(((PackageParser.Package) obj).packageName);
                return contains;
            }
        }, result, remainingPkgs, sortTemp, packageManagerService);
        final DexManager dexManager = packageManagerService.getDexManager();
        applyPackageFilter(new Predicate() { // from class: com.android.server.pm.-$$Lambda$PackageManagerServiceUtils$fMBP3pPR7BB2hICieRxkdNG-3H8
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean isAnyCodePathUsedByOtherApps;
                isAnyCodePathUsedByOtherApps = DexManager.this.getPackageUseInfoOrDefault(((PackageParser.Package) obj).packageName).isAnyCodePathUsedByOtherApps();
                return isAnyCodePathUsedByOtherApps;
            }
        }, result, remainingPkgs, sortTemp, packageManagerService);
        if (!remainingPkgs.isEmpty() && packageManagerService.isHistoricalPackageUsageAvailable()) {
            if (debug) {
                Log.i("PackageManager", "Looking at historical package use");
            }
            PackageParser.Package lastUsed = (PackageParser.Package) Collections.max(remainingPkgs, new Comparator() { // from class: com.android.server.pm.-$$Lambda$PackageManagerServiceUtils$whx96xO50U3fax1NRe1upTcx9jc
                @Override // java.util.Comparator
                public final int compare(Object obj, Object obj2) {
                    int compare;
                    compare = Long.compare(((PackageParser.Package) obj).getLatestForegroundPackageUseTimeInMills(), ((PackageParser.Package) obj2).getLatestForegroundPackageUseTimeInMills());
                    return compare;
                }
            });
            if (debug) {
                Log.i("PackageManager", "Taking package " + lastUsed.packageName + " as reference in time use");
            }
            long estimatedPreviousSystemUseTime = lastUsed.getLatestForegroundPackageUseTimeInMills();
            if (estimatedPreviousSystemUseTime != 0) {
                final long cutoffTime = estimatedPreviousSystemUseTime - 604800000;
                remainingPredicate = new Predicate() { // from class: com.android.server.pm.-$$Lambda$PackageManagerServiceUtils$p5q19y4-2x-i747j_hTNL1EMzt0
                    @Override // java.util.function.Predicate
                    public final boolean test(Object obj) {
                        return PackageManagerServiceUtils.lambda$getPackagesForDexopt$5(cutoffTime, (PackageParser.Package) obj);
                    }
                };
            } else {
                remainingPredicate = new Predicate() { // from class: com.android.server.pm.-$$Lambda$PackageManagerServiceUtils$Fz3elZ0VmMMv9-wl_G3AN15dUU8
                    @Override // java.util.function.Predicate
                    public final boolean test(Object obj) {
                        return PackageManagerServiceUtils.lambda$getPackagesForDexopt$6((PackageParser.Package) obj);
                    }
                };
            }
            sortPackagesByUsageDate(remainingPkgs, packageManagerService);
        } else {
            remainingPredicate = new Predicate() { // from class: com.android.server.pm.-$$Lambda$PackageManagerServiceUtils$hVRkjdaFuAMTY9J9JQ7JyWMYCHA
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return PackageManagerServiceUtils.lambda$getPackagesForDexopt$7((PackageParser.Package) obj);
                }
            };
        }
        applyPackageFilter(remainingPredicate, result, remainingPkgs, sortTemp, packageManagerService);
        if (debug) {
            Log.i("PackageManager", "Packages to be dexopted: " + packagesToString(result));
            Log.i("PackageManager", "Packages skipped from dexopt: " + packagesToString(remainingPkgs));
        }
        return result;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$getPackagesForDexopt$5(long cutoffTime, PackageParser.Package pkg) {
        return pkg.getLatestForegroundPackageUseTimeInMills() >= cutoffTime;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$getPackagesForDexopt$6(PackageParser.Package pkg) {
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$getPackagesForDexopt$7(PackageParser.Package pkg) {
        return true;
    }

    public static boolean isUnusedSinceTimeInMillis(long firstInstallTime, long currentTimeInMillis, long thresholdTimeinMillis, PackageDexUsage.PackageUseInfo packageUseInfo, long latestPackageUseTimeInMillis, long latestForegroundPackageUseTimeInMillis) {
        if (currentTimeInMillis - firstInstallTime < thresholdTimeinMillis) {
            return false;
        }
        boolean isActiveInForeground = currentTimeInMillis - latestForegroundPackageUseTimeInMillis < thresholdTimeinMillis;
        if (isActiveInForeground) {
            return false;
        }
        boolean isActiveInBackgroundAndUsedByOtherPackages = currentTimeInMillis - latestPackageUseTimeInMillis < thresholdTimeinMillis && packageUseInfo.isAnyCodePathUsedByOtherApps();
        return !isActiveInBackgroundAndUsedByOtherPackages;
    }

    public static String realpath(File path) throws IOException {
        try {
            return Os.realpath(path.getAbsolutePath());
        } catch (ErrnoException ee) {
            throw ee.rethrowAsIOException();
        }
    }

    public static String packagesToString(Collection<PackageParser.Package> c) {
        StringBuilder sb = new StringBuilder();
        for (PackageParser.Package pkg : c) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(pkg.packageName);
        }
        return sb.toString();
    }

    public static boolean checkISA(String isa) {
        String[] strArr;
        for (String abi : Build.SUPPORTED_ABIS) {
            if (VMRuntime.getInstructionSet(abi).equals(isa)) {
                return true;
            }
        }
        return false;
    }

    public static long getLastModifiedTime(PackageParser.Package pkg) {
        File srcFile = new File(pkg.codePath);
        if (!srcFile.isDirectory()) {
            return srcFile.lastModified();
        }
        File baseFile = new File(pkg.baseCodePath);
        long maxModifiedTime = baseFile.lastModified();
        if (pkg.splitCodePaths != null) {
            for (int i = pkg.splitCodePaths.length - 1; i >= 0; i--) {
                File splitFile = new File(pkg.splitCodePaths[i]);
                maxModifiedTime = Math.max(maxModifiedTime, splitFile.lastModified());
            }
        }
        return maxModifiedTime;
    }

    private static File getSettingsProblemFile() {
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        File fname = new File(systemDir, "uiderrors.txt");
        return fname;
    }

    public static void dumpCriticalInfo(ProtoOutputStream proto) {
        try {
            BufferedReader in = new BufferedReader(new FileReader(getSettingsProblemFile()));
            while (true) {
                String line = in.readLine();
                if (line != null) {
                    if (!line.contains("ignored: updated version")) {
                        proto.write(2237677961223L, line);
                    }
                } else {
                    $closeResource(null, in);
                    return;
                }
            }
        } catch (IOException e) {
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

    public static void dumpCriticalInfo(PrintWriter pw, String msg) {
        try {
            BufferedReader in = new BufferedReader(new FileReader(getSettingsProblemFile()));
            while (true) {
                String line = in.readLine();
                if (line != null) {
                    if (!line.contains("ignored: updated version")) {
                        if (msg != null) {
                            pw.print(msg);
                        }
                        pw.println(line);
                    }
                } else {
                    $closeResource(null, in);
                    return;
                }
            }
        } catch (IOException e) {
        }
    }

    public static void logCriticalInfo(int priority, String msg) {
        Slog.println(priority, "PackageManager", msg);
        EventLogTags.writePmCriticalInfo(msg);
        try {
            File fname = getSettingsProblemFile();
            FileOutputStream out = new FileOutputStream(fname, true);
            FastPrintWriter fastPrintWriter = new FastPrintWriter(out);
            SimpleDateFormat formatter = new SimpleDateFormat();
            String dateString = formatter.format(new Date(System.currentTimeMillis()));
            fastPrintWriter.println(dateString + ": " + msg);
            fastPrintWriter.close();
            FileUtils.setPermissions(fname.toString(), 508, -1, -1);
        } catch (IOException e) {
        }
    }

    public static void enforceShellRestriction(String restriction, int callingUid, int userHandle) {
        if (callingUid == 2000) {
            if (userHandle >= 0 && PackageManagerService.sUserManager.hasUserRestriction(restriction, userHandle)) {
                throw new SecurityException("Shell does not have permission to access user " + userHandle);
            } else if (userHandle < 0) {
                Slog.e("PackageManager", "Unable to check shell permission for user " + userHandle + "\n\t" + Debug.getCallers(3));
            }
        }
    }

    public static String deriveAbiOverride(String abiOverride, PackageSetting settings) {
        if ("-".equals(abiOverride)) {
            return null;
        }
        if (abiOverride != null) {
            return abiOverride;
        }
        if (settings == null) {
            return null;
        }
        String cpuAbiOverride = settings.cpuAbiOverrideString;
        return cpuAbiOverride;
    }

    public static int compareSignatures(Signature[] s1, Signature[] s2) {
        if (s1 == null) {
            if (s2 == null) {
                return 1;
            }
            return -1;
        } else if (s2 == null) {
            return -2;
        } else {
            if (s1.length != s2.length) {
                return -3;
            }
            if (s1.length == 1) {
                return s1[0].equals(s2[0]) ? 0 : -3;
            }
            ArraySet<Signature> set1 = new ArraySet<>();
            for (Signature sig : s1) {
                set1.add(sig);
            }
            ArraySet<Signature> set2 = new ArraySet<>();
            for (Signature sig2 : s2) {
                set2.add(sig2);
            }
            return set1.equals(set2) ? 0 : -3;
        }
    }

    private static boolean matchSignaturesCompat(String packageName, PackageSignatures packageSignatures, PackageParser.SigningDetails parsedSignatures) {
        Signature[] signatureArr;
        ArraySet<Signature> existingSet = new ArraySet<>();
        for (Signature sig : packageSignatures.mSigningDetails.signatures) {
            existingSet.add(sig);
        }
        ArraySet<Signature> scannedCompatSet = new ArraySet<>();
        for (Signature sig2 : parsedSignatures.signatures) {
            try {
                Signature[] chainSignatures = sig2.getChainSignatures();
                for (Signature chainSig : chainSignatures) {
                    scannedCompatSet.add(chainSig);
                }
            } catch (CertificateEncodingException e) {
                scannedCompatSet.add(sig2);
            }
        }
        if (scannedCompatSet.equals(existingSet)) {
            packageSignatures.mSigningDetails = parsedSignatures;
            return true;
        }
        if (parsedSignatures.hasPastSigningCertificates()) {
            logCriticalInfo(4, "Existing package " + packageName + " has flattened signing certificate chain. Unable to install newer version with rotated signing certificate.");
        }
        return false;
    }

    private static boolean matchSignaturesRecover(String packageName, PackageParser.SigningDetails existingSignatures, PackageParser.SigningDetails parsedSignatures, @PackageParser.SigningDetails.CertCapabilities int flags) {
        String msg = null;
        try {
            if (parsedSignatures.checkCapabilityRecover(existingSignatures, flags)) {
                logCriticalInfo(4, "Recovered effectively matching certificates for " + packageName);
                return true;
            }
        } catch (CertificateException e) {
            msg = e.getMessage();
        }
        logCriticalInfo(4, "Failed to recover certificates for " + packageName + ": " + msg);
        return false;
    }

    private static boolean matchSignatureInSystem(PackageSetting pkgSetting, PackageSetting disabledPkgSetting) {
        try {
            PackageParser.collectCertificates(disabledPkgSetting.pkg, true);
            if (!pkgSetting.signatures.mSigningDetails.checkCapability(disabledPkgSetting.signatures.mSigningDetails, 1) && !disabledPkgSetting.signatures.mSigningDetails.checkCapability(pkgSetting.signatures.mSigningDetails, 8)) {
                logCriticalInfo(6, "Updated system app mismatches cert on /system: " + pkgSetting.name);
                return false;
            }
            return true;
        } catch (PackageParser.PackageParserException e) {
            logCriticalInfo(6, "Failed to collect cert for " + pkgSetting.name + ": " + e.getMessage());
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean isApkVerityEnabled() {
        return SystemProperties.getInt("ro.apk_verity.mode", 0) == 2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean isLegacyApkVerityEnabled() {
        return SystemProperties.getInt("ro.apk_verity.mode", 0) == 1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean isApkVerificationForced(PackageSetting disabledPs) {
        return disabledPs != null && disabledPs.isPrivileged() && (isApkVerityEnabled() || isLegacyApkVerityEnabled());
    }

    public static boolean verifySignatures(PackageSetting pkgSetting, PackageSetting disabledPkgSetting, PackageParser.SigningDetails parsedSignatures, boolean compareCompat, boolean compareRecover) throws PackageManagerException {
        String packageName = pkgSetting.name;
        boolean compatMatch = false;
        boolean z = false;
        if (pkgSetting.signatures.mSigningDetails.signatures != null) {
            boolean match = parsedSignatures.checkCapability(pkgSetting.signatures.mSigningDetails, 1) || pkgSetting.signatures.mSigningDetails.checkCapability(parsedSignatures, 8);
            if (!match && compareCompat) {
                match = matchSignaturesCompat(packageName, pkgSetting.signatures, parsedSignatures);
                compatMatch = match;
            }
            if (!match && compareRecover) {
                match = matchSignaturesRecover(packageName, pkgSetting.signatures.mSigningDetails, parsedSignatures, 1) || matchSignaturesRecover(packageName, parsedSignatures, pkgSetting.signatures.mSigningDetails, 8);
            }
            if (!match && isApkVerificationForced(disabledPkgSetting)) {
                match = matchSignatureInSystem(pkgSetting, disabledPkgSetting);
            }
            if (!match) {
                throw new PackageManagerException(-7, "Package " + packageName + " signatures do not match previously installed version; ignoring!");
            }
        }
        if (pkgSetting.sharedUser != null && pkgSetting.sharedUser.signatures.mSigningDetails != PackageParser.SigningDetails.UNKNOWN) {
            boolean match2 = parsedSignatures.checkCapability(pkgSetting.sharedUser.signatures.mSigningDetails, 2) || pkgSetting.sharedUser.signatures.mSigningDetails.checkCapability(parsedSignatures, 2);
            if (!match2 && compareCompat) {
                match2 = matchSignaturesCompat(packageName, pkgSetting.sharedUser.signatures, parsedSignatures);
            }
            if (!match2 && compareRecover) {
                if (matchSignaturesRecover(packageName, pkgSetting.sharedUser.signatures.mSigningDetails, parsedSignatures, 2) || matchSignaturesRecover(packageName, parsedSignatures, pkgSetting.sharedUser.signatures.mSigningDetails, 2)) {
                    z = true;
                }
                match2 = z;
                compatMatch |= match2;
            }
            if (!match2) {
                throw new PackageManagerException(-8, "Package " + packageName + " has no signatures that match those in shared user " + pkgSetting.sharedUser.name + "; ignoring!");
            }
        }
        return compatMatch;
    }

    public static int decompressFile(File srcFile, File dstFile) throws ErrnoException {
        if (PackageManagerService.DEBUG_COMPRESSION) {
            Slog.i("PackageManager", "Decompress file; src: " + srcFile.getAbsolutePath() + ", dst: " + dstFile.getAbsolutePath());
        }
        try {
            InputStream fileIn = new GZIPInputStream(new FileInputStream(srcFile));
            OutputStream fileOut = new FileOutputStream(dstFile, false);
            FileUtils.copy(fileIn, fileOut);
            Os.chmod(dstFile.getAbsolutePath(), 420);
            $closeResource(null, fileOut);
            $closeResource(null, fileIn);
            return 1;
        } catch (IOException e) {
            logCriticalInfo(6, "Failed to decompress file; src: " + srcFile.getAbsolutePath() + ", dst: " + dstFile.getAbsolutePath());
            return RequestStatus.SYS_ETIMEDOUT;
        }
    }

    public static File[] getCompressedFiles(String codePath) {
        File stubCodePath = new File(codePath);
        String stubName = stubCodePath.getName();
        int idx = stubName.lastIndexOf(PackageManagerService.STUB_SUFFIX);
        if (idx < 0 || stubName.length() != PackageManagerService.STUB_SUFFIX.length() + idx) {
            return null;
        }
        File stubParentDir = stubCodePath.getParentFile();
        if (stubParentDir == null) {
            Slog.e("PackageManager", "Unable to determine stub parent dir for codePath: " + codePath);
            return null;
        }
        File compressedPath = new File(stubParentDir, stubName.substring(0, idx));
        File[] files = compressedPath.listFiles(new FilenameFilter() { // from class: com.android.server.pm.PackageManagerServiceUtils.1
            @Override // java.io.FilenameFilter
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(PackageManagerService.COMPRESSED_EXTENSION);
            }
        });
        if (PackageManagerService.DEBUG_COMPRESSION && files != null && files.length > 0) {
            Slog.i("PackageManager", "getCompressedFiles[" + codePath + "]: " + Arrays.toString(files));
        }
        return files;
    }

    public static boolean compressedFileExists(String codePath) {
        File[] compressedFiles = getCompressedFiles(codePath);
        return compressedFiles != null && compressedFiles.length > 0;
    }

    public static PackageInfoLite getMinimalPackageInfo(Context context, String packagePath, int flags, String abiOverride) {
        PackageInfoLite ret = new PackageInfoLite();
        if (packagePath == null) {
            Slog.i("PackageManager", "Invalid package file " + packagePath);
            ret.recommendedInstallLocation = -2;
            return ret;
        }
        File packageFile = new File(packagePath);
        try {
            PackageParser.PackageLite pkg = PackageParser.parsePackageLite(packageFile, 0);
            long sizeBytes = PackageHelper.calculateInstalledSize(pkg, abiOverride);
            int recommendedInstallLocation = PackageHelper.resolveInstallLocation(context, pkg.packageName, pkg.installLocation, sizeBytes, flags);
            ret.packageName = pkg.packageName;
            ret.splitNames = pkg.splitNames;
            ret.versionCode = pkg.versionCode;
            ret.versionCodeMajor = pkg.versionCodeMajor;
            ret.baseRevisionCode = pkg.baseRevisionCode;
            ret.splitRevisionCodes = pkg.splitRevisionCodes;
            ret.installLocation = pkg.installLocation;
            ret.verifiers = pkg.verifiers;
            ret.recommendedInstallLocation = recommendedInstallLocation;
            ret.multiArch = pkg.multiArch;
            return ret;
        } catch (PackageParser.PackageParserException | IOException e) {
            Slog.w("PackageManager", "Failed to parse package at " + packagePath + ": " + e);
            if (!packageFile.exists()) {
                ret.recommendedInstallLocation = -6;
            } else {
                ret.recommendedInstallLocation = -2;
            }
            return ret;
        }
    }

    public static long calculateInstalledSize(String packagePath, String abiOverride) {
        File packageFile = new File(packagePath);
        try {
            PackageParser.PackageLite pkg = PackageParser.parsePackageLite(packageFile, 0);
            return PackageHelper.calculateInstalledSize(pkg, abiOverride);
        } catch (PackageParser.PackageParserException | IOException e) {
            Slog.w("PackageManager", "Failed to calculate installed size: " + e);
            return -1L;
        }
    }

    public static boolean isDowngradePermitted(int installFlags, int applicationFlags) {
        boolean downgradeRequested = (installFlags & 128) != 0;
        if (downgradeRequested) {
            boolean isDebuggable = Build.IS_DEBUGGABLE || (applicationFlags & 2) != 0;
            return isDebuggable || (1048576 & installFlags) != 0;
        }
        return false;
    }

    public static int copyPackage(String packagePath, File targetDir) {
        if (packagePath == null) {
            return -3;
        }
        try {
            File packageFile = new File(packagePath);
            PackageParser.PackageLite pkg = PackageParser.parsePackageLite(packageFile, 0);
            copyFile(pkg.baseCodePath, targetDir, "base.apk");
            if (!ArrayUtils.isEmpty(pkg.splitNames)) {
                for (int i = 0; i < pkg.splitNames.length; i++) {
                    String str = pkg.splitCodePaths[i];
                    copyFile(str, targetDir, "split_" + pkg.splitNames[i] + ".apk");
                }
                return 1;
            }
            return 1;
        } catch (PackageParser.PackageParserException | ErrnoException | IOException e) {
            Slog.w("PackageManager", "Failed to copy package at " + packagePath + ": " + e);
            return -4;
        }
    }

    private static void copyFile(String sourcePath, File targetDir, String targetName) throws ErrnoException, IOException {
        if (!FileUtils.isValidExtFilename(targetName)) {
            throw new IllegalArgumentException("Invalid filename: " + targetName);
        }
        Slog.d("PackageManager", "Copying " + sourcePath + " to " + targetName);
        File targetFile = new File(targetDir, targetName);
        FileDescriptor targetFd = Os.open(targetFile.getAbsolutePath(), OsConstants.O_RDWR | OsConstants.O_CREAT, 420);
        Os.chmod(targetFile.getAbsolutePath(), 420);
        FileInputStream source = null;
        try {
            source = new FileInputStream(sourcePath);
            FileUtils.copy(source.getFD(), targetFd);
        } finally {
            IoUtils.closeQuietly(source);
        }
    }
}
