package com.android.timezone.distro.installer;

import android.util.Slog;
import com.android.timezone.distro.DistroException;
import com.android.timezone.distro.DistroVersion;
import com.android.timezone.distro.FileUtils;
import com.android.timezone.distro.StagedDistroOperation;
import com.android.timezone.distro.TimeZoneDistro;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import libcore.timezone.TimeZoneFinder;
import libcore.timezone.TzDataSetVersion;
import libcore.timezone.ZoneInfoDB;

/* loaded from: classes2.dex */
public class TimeZoneDistroInstaller {
    private static final String CURRENT_TZ_DATA_DIR_NAME = "current";
    public static final int INSTALL_FAIL_BAD_DISTRO_FORMAT_VERSION = 2;
    public static final int INSTALL_FAIL_BAD_DISTRO_STRUCTURE = 1;
    public static final int INSTALL_FAIL_RULES_TOO_OLD = 3;
    public static final int INSTALL_FAIL_VALIDATION_ERROR = 4;
    public static final int INSTALL_SUCCESS = 0;
    private static final String OLD_TZ_DATA_DIR_NAME = "old";
    private static final String STAGED_TZ_DATA_DIR_NAME = "staged";
    public static final int UNINSTALL_FAIL = 2;
    public static final int UNINSTALL_NOTHING_INSTALLED = 1;
    public static final int UNINSTALL_SUCCESS = 0;
    public static final String UNINSTALL_TOMBSTONE_FILE_NAME = "STAGED_UNINSTALL_TOMBSTONE";
    private static final String WORKING_DIR_NAME = "working";
    private final File baseVersionFile;
    private final File currentTzDataDir;
    private final String logTag;
    private final File oldStagedDataDir;
    private final File stagedTzDataDir;
    private final File workingDir;

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes2.dex */
    private @interface InstallResultType {
    }

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes2.dex */
    private @interface UninstallResultType {
    }

    public TimeZoneDistroInstaller(String logTag, File baseVersionFile, File installDir) {
        this.logTag = logTag;
        this.baseVersionFile = baseVersionFile;
        this.oldStagedDataDir = new File(installDir, OLD_TZ_DATA_DIR_NAME);
        this.stagedTzDataDir = new File(installDir, STAGED_TZ_DATA_DIR_NAME);
        this.currentTzDataDir = new File(installDir, CURRENT_TZ_DATA_DIR_NAME);
        this.workingDir = new File(installDir, WORKING_DIR_NAME);
    }

    File getOldStagedDataDir() {
        return this.oldStagedDataDir;
    }

    File getStagedTzDataDir() {
        return this.stagedTzDataDir;
    }

    File getCurrentTzDataDir() {
        return this.currentTzDataDir;
    }

    File getWorkingDir() {
        return this.workingDir;
    }

    public int stageInstallWithErrorCode(TimeZoneDistro distro) throws IOException {
        File file;
        File file2;
        if (this.oldStagedDataDir.exists()) {
            FileUtils.deleteRecursive(this.oldStagedDataDir);
        }
        if (this.workingDir.exists()) {
            FileUtils.deleteRecursive(this.workingDir);
        }
        Slog.i(this.logTag, "Unpacking / verifying time zone update");
        try {
            unpackDistro(distro, this.workingDir);
            try {
                DistroVersion distroVersion = readDistroVersion(this.workingDir);
                if (distroVersion == null) {
                    Slog.i(this.logTag, "Update not applied: Distro version could not be loaded");
                    return 1;
                }
                try {
                    TzDataSetVersion distroTzDataSetVersion = new TzDataSetVersion(distroVersion.formatMajorVersion, distroVersion.formatMinorVersion, distroVersion.rulesVersion, distroVersion.revision);
                    if (!TzDataSetVersion.isCompatibleWithThisDevice(distroTzDataSetVersion)) {
                        String str = this.logTag;
                        Slog.i(str, "Update not applied: Distro format version check failed: " + distroVersion);
                        return 2;
                    } else if (!checkDistroDataFilesExist(this.workingDir)) {
                        Slog.i(this.logTag, "Update not applied: Distro is missing required data file(s)");
                        return 1;
                    } else if (!checkDistroRulesNewerThanBase(this.baseVersionFile, distroVersion)) {
                        Slog.i(this.logTag, "Update not applied: Distro rules version check failed");
                        return 3;
                    } else {
                        File zoneInfoFile = new File(this.workingDir, TimeZoneDistro.TZDATA_FILE_NAME);
                        ZoneInfoDB.TzData tzData = ZoneInfoDB.TzData.loadTzData(zoneInfoFile.getPath());
                        if (tzData == null) {
                            String str2 = this.logTag;
                            Slog.i(str2, "Update not applied: " + zoneInfoFile + " could not be loaded");
                            return 4;
                        }
                        try {
                            tzData.validate();
                            tzData.close();
                            File tzLookupFile = new File(this.workingDir, TimeZoneDistro.TZLOOKUP_FILE_NAME);
                            if (!tzLookupFile.exists()) {
                                String str3 = this.logTag;
                                Slog.i(str3, "Update not applied: " + tzLookupFile + " does not exist");
                                return 1;
                            }
                            try {
                                TimeZoneFinder timeZoneFinder = TimeZoneFinder.createInstance(tzLookupFile.getPath());
                                timeZoneFinder.validate();
                                Slog.i(this.logTag, "Applying time zone update");
                                FileUtils.makeDirectoryWorldAccessible(this.workingDir);
                                if (this.stagedTzDataDir.exists()) {
                                    String str4 = this.logTag;
                                    Slog.i(str4, "Moving " + this.stagedTzDataDir + " to " + this.oldStagedDataDir);
                                    FileUtils.rename(this.stagedTzDataDir, this.oldStagedDataDir);
                                } else {
                                    String str5 = this.logTag;
                                    Slog.i(str5, "Nothing to unstage at " + this.stagedTzDataDir);
                                }
                                String str6 = this.logTag;
                                Slog.i(str6, "Moving " + this.workingDir + " to " + this.stagedTzDataDir);
                                FileUtils.rename(this.workingDir, this.stagedTzDataDir);
                                String str7 = this.logTag;
                                Slog.i(str7, "Install staged: " + this.stagedTzDataDir + " successfully created");
                                return 0;
                            } catch (IOException e) {
                                String str8 = this.logTag;
                                Slog.i(str8, "Update not applied: " + tzLookupFile + " failed validation", e);
                                return 4;
                            }
                        } catch (IOException e2) {
                            String str9 = this.logTag;
                            Slog.i(str9, "Update not applied: " + zoneInfoFile + " failed validation", e2);
                            tzData.close();
                            return 4;
                        }
                    }
                } catch (TzDataSetVersion.TzDataSetException e3) {
                    Slog.i(this.logTag, "Update not applied: Distro version could not be converted", e3);
                    return 1;
                }
            } catch (DistroException e4) {
                String str10 = this.logTag;
                Slog.i(str10, "Invalid distro version: " + e4.getMessage());
                return 1;
            }
        } finally {
            deleteBestEffort(this.oldStagedDataDir);
            deleteBestEffort(this.workingDir);
        }
    }

    public int stageUninstall() throws IOException {
        Slog.i(this.logTag, "Uninstalling time zone update");
        if (this.oldStagedDataDir.exists()) {
            FileUtils.deleteRecursive(this.oldStagedDataDir);
        }
        if (this.workingDir.exists()) {
            FileUtils.deleteRecursive(this.workingDir);
        }
        try {
            if (!this.stagedTzDataDir.exists()) {
                String str = this.logTag;
                Slog.i(str, "Nothing to unstage at " + this.stagedTzDataDir);
            } else {
                String str2 = this.logTag;
                Slog.i(str2, "Moving " + this.stagedTzDataDir + " to " + this.oldStagedDataDir);
                FileUtils.rename(this.stagedTzDataDir, this.oldStagedDataDir);
            }
            if (!this.currentTzDataDir.exists()) {
                String str3 = this.logTag;
                Slog.i(str3, "Nothing to uninstall at " + this.currentTzDataDir);
                return 1;
            }
            FileUtils.ensureDirectoriesExist(this.workingDir, true);
            FileUtils.createEmptyFile(new File(this.workingDir, UNINSTALL_TOMBSTONE_FILE_NAME));
            String str4 = this.logTag;
            Slog.i(str4, "Moving " + this.workingDir + " to " + this.stagedTzDataDir);
            FileUtils.rename(this.workingDir, this.stagedTzDataDir);
            String str5 = this.logTag;
            Slog.i(str5, "Uninstall staged: " + this.stagedTzDataDir + " successfully created");
            return 0;
        } finally {
            deleteBestEffort(this.oldStagedDataDir);
            deleteBestEffort(this.workingDir);
        }
    }

    public DistroVersion getInstalledDistroVersion() throws DistroException, IOException {
        if (!this.currentTzDataDir.exists()) {
            return null;
        }
        return readDistroVersion(this.currentTzDataDir);
    }

    public StagedDistroOperation getStagedDistroOperation() throws DistroException, IOException {
        if (!this.stagedTzDataDir.exists()) {
            return null;
        }
        if (new File(this.stagedTzDataDir, UNINSTALL_TOMBSTONE_FILE_NAME).exists()) {
            return StagedDistroOperation.uninstall();
        }
        return StagedDistroOperation.install(readDistroVersion(this.stagedTzDataDir));
    }

    public TzDataSetVersion readBaseVersion() throws IOException {
        return readBaseVersion(this.baseVersionFile);
    }

    private TzDataSetVersion readBaseVersion(File baseVersionFile) throws IOException {
        if (!baseVersionFile.exists()) {
            String str = this.logTag;
            Slog.i(str, "version file cannot be found in " + baseVersionFile);
            throw new FileNotFoundException("base version file does not exist: " + baseVersionFile);
        }
        try {
            return TzDataSetVersion.readFromFile(baseVersionFile);
        } catch (TzDataSetVersion.TzDataSetException e) {
            throw new IOException("Unable to read: " + baseVersionFile, e);
        }
    }

    private void deleteBestEffort(File dir) {
        if (dir.exists()) {
            String str = this.logTag;
            Slog.i(str, "Deleting " + dir);
            try {
                FileUtils.deleteRecursive(dir);
            } catch (IOException e) {
                String str2 = this.logTag;
                Slog.w(str2, "Unable to delete " + dir, e);
            }
        }
    }

    private void unpackDistro(TimeZoneDistro distro, File targetDir) throws IOException {
        String str = this.logTag;
        Slog.i(str, "Unpacking update content to: " + targetDir);
        distro.extractTo(targetDir);
    }

    private boolean checkDistroDataFilesExist(File unpackedContentDir) throws IOException {
        Slog.i(this.logTag, "Verifying distro contents");
        return FileUtils.filesExist(unpackedContentDir, TimeZoneDistro.TZDATA_FILE_NAME, TimeZoneDistro.ICU_DATA_FILE_NAME);
    }

    private DistroVersion readDistroVersion(File distroDir) throws DistroException, IOException {
        String str = this.logTag;
        Slog.d(str, "Reading distro format version: " + distroDir);
        File distroVersionFile = new File(distroDir, TimeZoneDistro.DISTRO_VERSION_FILE_NAME);
        if (!distroVersionFile.exists()) {
            throw new DistroException("No distro version file found: " + distroVersionFile);
        }
        byte[] versionBytes = FileUtils.readBytes(distroVersionFile, DistroVersion.DISTRO_VERSION_FILE_LENGTH);
        return DistroVersion.fromBytes(versionBytes);
    }

    private boolean checkDistroRulesNewerThanBase(File baseVersionFile, DistroVersion distroVersion) throws IOException {
        Slog.i(this.logTag, "Reading base time zone rules version");
        TzDataSetVersion baseVersion = readBaseVersion(baseVersionFile);
        String baseRulesVersion = baseVersion.rulesVersion;
        String distroRulesVersion = distroVersion.rulesVersion;
        boolean canApply = distroRulesVersion.compareTo(baseRulesVersion) >= 0;
        if (!canApply) {
            String str = this.logTag;
            Slog.i(str, "Failed rules version check: distroRulesVersion=" + distroRulesVersion + ", baseRulesVersion=" + baseRulesVersion);
        } else {
            String str2 = this.logTag;
            Slog.i(str2, "Passed rules version check: distroRulesVersion=" + distroRulesVersion + ", baseRulesVersion=" + baseRulesVersion);
        }
        return canApply;
    }
}
