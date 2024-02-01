package com.android.server.pm;

import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.PackageParser;
import android.content.pm.PackageUserState;
import android.content.pm.Signature;
import android.os.PersistableBundle;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.permission.PermissionsState;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
/* loaded from: classes.dex */
public abstract class PackageSettingBase extends SettingBase {
    int categoryHint;
    List<String> childPackageNames;
    File codePath;
    String codePathString;
    String cpuAbiOverrideString;
    long firstInstallTime;
    boolean installPermissionsFixed;
    String installerPackageName;
    boolean isOrphaned;
    PackageKeySetData keySetData;
    long lastUpdateTime;
    @Deprecated
    String legacyNativeLibraryPathString;
    public final String name;
    Set<String> oldCodePaths;
    String parentPackageName;
    String primaryCpuAbiString;
    final String realName;
    File resourcePath;
    String resourcePathString;
    String secondaryCpuAbiString;
    PackageSignatures signatures;
    long timeStamp;
    boolean uidError;
    boolean updateAvailable;
    private final SparseArray<PackageUserState> userState;
    String[] usesStaticLibraries;
    long[] usesStaticLibrariesVersions;
    IntentFilterVerificationInfo verificationInfo;
    long versionCode;
    String volumeUuid;
    private static final int[] EMPTY_INT_ARRAY = new int[0];
    static final PackageUserState DEFAULT_USER_STATE = new PackageUserState();

    @Override // com.android.server.pm.SettingBase
    public /* bridge */ /* synthetic */ void copyFrom(SettingBase settingBase) {
        super.copyFrom(settingBase);
    }

    @Override // com.android.server.pm.SettingBase
    public /* bridge */ /* synthetic */ PermissionsState getPermissionsState() {
        return super.getPermissionsState();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageSettingBase(String name, String realName, File codePath, File resourcePath, String legacyNativeLibraryPathString, String primaryCpuAbiString, String secondaryCpuAbiString, String cpuAbiOverrideString, long pVersionCode, int pkgFlags, int pkgPrivateFlags, String parentPackageName, List<String> childPackageNames, String[] usesStaticLibraries, long[] usesStaticLibrariesVersions) {
        super(pkgFlags, pkgPrivateFlags);
        this.keySetData = new PackageKeySetData();
        this.userState = new SparseArray<>();
        this.categoryHint = -1;
        this.name = name;
        this.realName = realName;
        this.parentPackageName = parentPackageName;
        this.childPackageNames = childPackageNames != null ? new ArrayList(childPackageNames) : null;
        this.usesStaticLibraries = usesStaticLibraries;
        this.usesStaticLibrariesVersions = usesStaticLibrariesVersions;
        init(codePath, resourcePath, legacyNativeLibraryPathString, primaryCpuAbiString, secondaryCpuAbiString, cpuAbiOverrideString, pVersionCode);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageSettingBase(PackageSettingBase base, String realName) {
        super(base);
        this.keySetData = new PackageKeySetData();
        this.userState = new SparseArray<>();
        this.categoryHint = -1;
        this.name = base.name;
        this.realName = realName;
        doCopy(base);
    }

    void init(File codePath, File resourcePath, String legacyNativeLibraryPathString, String primaryCpuAbiString, String secondaryCpuAbiString, String cpuAbiOverrideString, long pVersionCode) {
        this.codePath = codePath;
        this.codePathString = codePath.toString();
        this.resourcePath = resourcePath;
        this.resourcePathString = resourcePath.toString();
        this.legacyNativeLibraryPathString = legacyNativeLibraryPathString;
        this.primaryCpuAbiString = primaryCpuAbiString;
        this.secondaryCpuAbiString = secondaryCpuAbiString;
        this.cpuAbiOverrideString = cpuAbiOverrideString;
        this.versionCode = pVersionCode;
        this.signatures = new PackageSignatures();
    }

    public void setInstallerPackageName(String packageName) {
        this.installerPackageName = packageName;
    }

    public String getInstallerPackageName() {
        return this.installerPackageName;
    }

    public void setVolumeUuid(String volumeUuid) {
        this.volumeUuid = volumeUuid;
    }

    public String getVolumeUuid() {
        return this.volumeUuid;
    }

    public void setTimeStamp(long newStamp) {
        this.timeStamp = newStamp;
    }

    public void setUpdateAvailable(boolean updateAvailable) {
        this.updateAvailable = updateAvailable;
    }

    public boolean isUpdateAvailable() {
        return this.updateAvailable;
    }

    public boolean isSharedUser() {
        return false;
    }

    public Signature[] getSignatures() {
        return this.signatures.mSigningDetails.signatures;
    }

    public PackageParser.SigningDetails getSigningDetails() {
        return this.signatures.mSigningDetails;
    }

    public void copyFrom(PackageSettingBase orig) {
        super.copyFrom((SettingBase) orig);
        doCopy(orig);
    }

    private void doCopy(PackageSettingBase orig) {
        String[] strArr;
        this.childPackageNames = orig.childPackageNames != null ? new ArrayList(orig.childPackageNames) : null;
        this.codePath = orig.codePath;
        this.codePathString = orig.codePathString;
        this.cpuAbiOverrideString = orig.cpuAbiOverrideString;
        this.firstInstallTime = orig.firstInstallTime;
        this.installPermissionsFixed = orig.installPermissionsFixed;
        this.installerPackageName = orig.installerPackageName;
        this.isOrphaned = orig.isOrphaned;
        this.keySetData = orig.keySetData;
        this.lastUpdateTime = orig.lastUpdateTime;
        this.legacyNativeLibraryPathString = orig.legacyNativeLibraryPathString;
        this.parentPackageName = orig.parentPackageName;
        this.primaryCpuAbiString = orig.primaryCpuAbiString;
        this.resourcePath = orig.resourcePath;
        this.resourcePathString = orig.resourcePathString;
        this.secondaryCpuAbiString = orig.secondaryCpuAbiString;
        this.signatures = orig.signatures;
        this.timeStamp = orig.timeStamp;
        this.uidError = orig.uidError;
        this.userState.clear();
        for (int i = 0; i < orig.userState.size(); i++) {
            this.userState.put(orig.userState.keyAt(i), orig.userState.valueAt(i));
        }
        this.verificationInfo = orig.verificationInfo;
        this.versionCode = orig.versionCode;
        this.volumeUuid = orig.volumeUuid;
        this.categoryHint = orig.categoryHint;
        if (orig.usesStaticLibraries != null) {
            strArr = (String[]) Arrays.copyOf(orig.usesStaticLibraries, orig.usesStaticLibraries.length);
        } else {
            strArr = null;
        }
        this.usesStaticLibraries = strArr;
        this.usesStaticLibrariesVersions = orig.usesStaticLibrariesVersions != null ? Arrays.copyOf(orig.usesStaticLibrariesVersions, orig.usesStaticLibrariesVersions.length) : null;
        this.updateAvailable = orig.updateAvailable;
    }

    private PackageUserState modifyUserState(int userId) {
        PackageUserState state = this.userState.get(userId);
        if (state == null) {
            PackageUserState state2 = new PackageUserState();
            this.userState.put(userId, state2);
            return state2;
        }
        return state;
    }

    public PackageUserState readUserState(int userId) {
        PackageUserState state = this.userState.get(userId);
        if (state == null) {
            return DEFAULT_USER_STATE;
        }
        state.categoryHint = this.categoryHint;
        return state;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setEnabled(int state, int userId, String callingPackage) {
        PackageUserState st = modifyUserState(userId);
        st.enabled = state;
        st.lastDisableAppCaller = callingPackage;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getEnabled(int userId) {
        return readUserState(userId).enabled;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getLastDisabledAppCaller(int userId) {
        return readUserState(userId).lastDisableAppCaller;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setInstalled(boolean inst, int userId) {
        modifyUserState(userId).installed = inst;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getInstalled(int userId) {
        return readUserState(userId).installed;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getInstallReason(int userId) {
        return readUserState(userId).installReason;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setInstallReason(int installReason, int userId) {
        modifyUserState(userId).installReason = installReason;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setOverlayPaths(List<String> overlayPaths, int userId) {
        modifyUserState(userId).overlayPaths = overlayPaths == null ? null : (String[]) overlayPaths.toArray(new String[overlayPaths.size()]);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String[] getOverlayPaths(int userId) {
        return readUserState(userId).overlayPaths;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public SparseArray<PackageUserState> getUserState() {
        return this.userState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAnyInstalled(int[] users) {
        for (int user : users) {
            if (readUserState(user).installed) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int[] queryInstalledUsers(int[] users, boolean installed) {
        int num = 0;
        for (int user : users) {
            if (getInstalled(user) == installed) {
                num++;
            }
        }
        int[] res = new int[num];
        int num2 = 0;
        for (int user2 : users) {
            if (getInstalled(user2) == installed) {
                res[num2] = user2;
                num2++;
            }
        }
        return res;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getCeDataInode(int userId) {
        return readUserState(userId).ceDataInode;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setCeDataInode(long ceDataInode, int userId) {
        modifyUserState(userId).ceDataInode = ceDataInode;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getStopped(int userId) {
        return readUserState(userId).stopped;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setStopped(boolean stop, int userId) {
        modifyUserState(userId).stopped = stop;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getNotLaunched(int userId) {
        return readUserState(userId).notLaunched;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setNotLaunched(boolean stop, int userId) {
        modifyUserState(userId).notLaunched = stop;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getHidden(int userId) {
        return readUserState(userId).hidden;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setHidden(boolean hidden, int userId) {
        modifyUserState(userId).hidden = hidden;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getSuspended(int userId) {
        return readUserState(userId).suspended;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setSuspended(boolean suspended, String suspendingPackage, String dialogMessage, PersistableBundle appExtras, PersistableBundle launcherExtras, int userId) {
        PackageUserState existingUserState = modifyUserState(userId);
        existingUserState.suspended = suspended;
        existingUserState.suspendingPackage = suspended ? suspendingPackage : null;
        existingUserState.dialogMessage = suspended ? dialogMessage : null;
        existingUserState.suspendedAppExtras = suspended ? appExtras : null;
        existingUserState.suspendedLauncherExtras = suspended ? launcherExtras : null;
    }

    public boolean getInstantApp(int userId) {
        return readUserState(userId).instantApp;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setInstantApp(boolean instantApp, int userId) {
        modifyUserState(userId).instantApp = instantApp;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getVirtulalPreload(int userId) {
        return readUserState(userId).virtualPreload;
    }

    void setVirtualPreload(boolean virtualPreload, int userId) {
        modifyUserState(userId).virtualPreload = virtualPreload;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setUserState(int userId, long ceDataInode, int enabled, boolean installed, boolean stopped, boolean notLaunched, boolean hidden, boolean suspended, String suspendingPackage, String dialogMessage, PersistableBundle suspendedAppExtras, PersistableBundle suspendedLauncherExtras, boolean instantApp, boolean virtualPreload, String lastDisableAppCaller, ArraySet<String> enabledComponents, ArraySet<String> disabledComponents, int domainVerifState, int linkGeneration, int installReason, String harmfulAppWarning) {
        PackageUserState state = modifyUserState(userId);
        state.ceDataInode = ceDataInode;
        state.enabled = enabled;
        state.installed = installed;
        state.stopped = stopped;
        state.notLaunched = notLaunched;
        state.hidden = hidden;
        state.suspended = suspended;
        state.suspendingPackage = suspendingPackage;
        state.dialogMessage = dialogMessage;
        state.suspendedAppExtras = suspendedAppExtras;
        state.suspendedLauncherExtras = suspendedLauncherExtras;
        state.lastDisableAppCaller = lastDisableAppCaller;
        state.enabledComponents = enabledComponents;
        state.disabledComponents = disabledComponents;
        state.domainVerificationStatus = domainVerifState;
        state.appLinkGeneration = linkGeneration;
        state.installReason = installReason;
        state.instantApp = instantApp;
        state.virtualPreload = virtualPreload;
        state.harmfulAppWarning = harmfulAppWarning;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ArraySet<String> getEnabledComponents(int userId) {
        return readUserState(userId).enabledComponents;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ArraySet<String> getDisabledComponents(int userId) {
        return readUserState(userId).disabledComponents;
    }

    void setEnabledComponents(ArraySet<String> components, int userId) {
        modifyUserState(userId).enabledComponents = components;
    }

    void setDisabledComponents(ArraySet<String> components, int userId) {
        modifyUserState(userId).disabledComponents = components;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setEnabledComponentsCopy(ArraySet<String> components, int userId) {
        modifyUserState(userId).enabledComponents = components != null ? new ArraySet((ArraySet) components) : null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDisabledComponentsCopy(ArraySet<String> components, int userId) {
        modifyUserState(userId).disabledComponents = components != null ? new ArraySet((ArraySet) components) : null;
    }

    PackageUserState modifyUserStateComponents(int userId, boolean disabled, boolean enabled) {
        PackageUserState state = modifyUserState(userId);
        if (disabled && state.disabledComponents == null) {
            state.disabledComponents = new ArraySet(1);
        }
        if (enabled && state.enabledComponents == null) {
            state.enabledComponents = new ArraySet(1);
        }
        return state;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addDisabledComponent(String componentClassName, int userId) {
        modifyUserStateComponents(userId, true, false).disabledComponents.add(componentClassName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addEnabledComponent(String componentClassName, int userId) {
        modifyUserStateComponents(userId, false, true).enabledComponents.add(componentClassName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean enableComponentLPw(String componentClassName, int userId) {
        boolean changed = false;
        PackageUserState state = modifyUserStateComponents(userId, false, true);
        if (state.disabledComponents != null) {
            changed = state.disabledComponents.remove(componentClassName);
        }
        return changed | state.enabledComponents.add(componentClassName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean disableComponentLPw(String componentClassName, int userId) {
        boolean changed = false;
        PackageUserState state = modifyUserStateComponents(userId, true, false);
        if (state.enabledComponents != null) {
            changed = state.enabledComponents.remove(componentClassName);
        }
        return changed | state.disabledComponents.add(componentClassName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean restoreComponentLPw(String componentClassName, int userId) {
        PackageUserState state = modifyUserStateComponents(userId, true, true);
        boolean changed = state.disabledComponents != null ? state.disabledComponents.remove(componentClassName) : false;
        return changed | (state.enabledComponents != null ? state.enabledComponents.remove(componentClassName) : false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getCurrentEnabledStateLPr(String componentName, int userId) {
        PackageUserState state = readUserState(userId);
        if (state.enabledComponents != null && state.enabledComponents.contains(componentName)) {
            return 1;
        }
        if (state.disabledComponents != null && state.disabledComponents.contains(componentName)) {
            return 2;
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeUser(int userId) {
        this.userState.delete(userId);
    }

    public int[] getNotInstalledUserIds() {
        int userStateCount = this.userState.size();
        int count = 0;
        for (int count2 = 0; count2 < userStateCount; count2++) {
            if (!this.userState.valueAt(count2).installed) {
                count++;
            }
        }
        if (count == 0) {
            return EMPTY_INT_ARRAY;
        }
        int[] excludedUserIds = new int[count];
        int idx = 0;
        for (int i = 0; i < userStateCount; i++) {
            if (!this.userState.valueAt(i).installed) {
                excludedUserIds[idx] = this.userState.keyAt(i);
                idx++;
            }
        }
        return excludedUserIds;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public IntentFilterVerificationInfo getIntentFilterVerificationInfo() {
        return this.verificationInfo;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setIntentFilterVerificationInfo(IntentFilterVerificationInfo info) {
        this.verificationInfo = info;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getDomainVerificationStatusForUser(int userId) {
        PackageUserState state = readUserState(userId);
        long result = state.appLinkGeneration;
        return result | (state.domainVerificationStatus << 32);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDomainVerificationStatusForUser(int status, int generation, int userId) {
        PackageUserState state = modifyUserState(userId);
        state.domainVerificationStatus = status;
        if (status == 2) {
            state.appLinkGeneration = generation;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearDomainVerificationStatusForUser(int userId) {
        modifyUserState(userId).domainVerificationStatus = 0;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void writeUsersInfoToProto(ProtoOutputStream proto, long fieldId) {
        int installType;
        int count = this.userState.size();
        for (int i = 0; i < count; i++) {
            long userToken = proto.start(fieldId);
            int userId = this.userState.keyAt(i);
            PackageUserState state = this.userState.valueAt(i);
            proto.write(1120986464257L, userId);
            if (state.instantApp) {
                installType = 2;
            } else if (state.installed) {
                installType = 1;
            } else {
                installType = 0;
            }
            proto.write(1159641169922L, installType);
            proto.write(1133871366147L, state.hidden);
            proto.write(1133871366148L, state.suspended);
            if (state.suspended) {
                proto.write(1138166333449L, state.suspendingPackage);
            }
            proto.write(1133871366149L, state.stopped);
            proto.write(1133871366150L, !state.notLaunched);
            proto.write(1159641169927L, state.enabled);
            proto.write(1138166333448L, state.lastDisableAppCaller);
            proto.end(userToken);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setHarmfulAppWarning(int userId, String harmfulAppWarning) {
        PackageUserState userState = modifyUserState(userId);
        userState.harmfulAppWarning = harmfulAppWarning;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getHarmfulAppWarning(int userId) {
        PackageUserState userState = readUserState(userId);
        return userState.harmfulAppWarning;
    }
}
