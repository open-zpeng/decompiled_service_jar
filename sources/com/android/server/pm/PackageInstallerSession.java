package com.android.server.pm;

import android.app.admin.DevicePolicyEventLogger;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.dex.DexMetadataHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.biometrics.fingerprint.V2_1.RequestStatus;
import android.os.Binder;
import android.os.Bundle;
import android.os.FileBridge;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.os.RevocableFileDescriptor;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.Int64Ref;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.ExceptionUtils;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.apk.ApkSignatureVerifier;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.content.PackageHelper;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.server.LocalServices;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.pm.Installer;
import com.android.server.pm.PackageInstallerService;
import com.android.server.pm.PackageInstallerSession;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.dex.DexManager;
import com.android.server.security.VerityUtils;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import libcore.io.IoUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

/* loaded from: classes.dex */
public class PackageInstallerSession extends IPackageInstallerSession.Stub {
    private static final String ATTR_ABI_OVERRIDE = "abiOverride";
    @Deprecated
    private static final String ATTR_APP_ICON = "appIcon";
    private static final String ATTR_APP_LABEL = "appLabel";
    private static final String ATTR_APP_PACKAGE_NAME = "appPackageName";
    private static final String ATTR_COMMITTED = "committed";
    private static final String ATTR_CREATED_MILLIS = "createdMillis";
    private static final String ATTR_INSTALLER_PACKAGE_NAME = "installerPackageName";
    private static final String ATTR_INSTALLER_UID = "installerUid";
    private static final String ATTR_INSTALL_FLAGS = "installFlags";
    private static final String ATTR_INSTALL_LOCATION = "installLocation";
    private static final String ATTR_INSTALL_REASON = "installRason";
    private static final String ATTR_IS_APPLIED = "isApplied";
    private static final String ATTR_IS_FAILED = "isFailed";
    private static final String ATTR_IS_READY = "isReady";
    private static final String ATTR_MODE = "mode";
    private static final String ATTR_MULTI_PACKAGE = "multiPackage";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_ORIGINATING_UID = "originatingUid";
    private static final String ATTR_ORIGINATING_URI = "originatingUri";
    private static final String ATTR_PARENT_SESSION_ID = "parentSessionId";
    private static final String ATTR_PREPARED = "prepared";
    private static final String ATTR_REFERRER_URI = "referrerUri";
    private static final String ATTR_SEALED = "sealed";
    private static final String ATTR_SESSION_ID = "sessionId";
    private static final String ATTR_SESSION_STAGE_CID = "sessionStageCid";
    private static final String ATTR_SESSION_STAGE_DIR = "sessionStageDir";
    private static final String ATTR_SIZE_BYTES = "sizeBytes";
    private static final String ATTR_STAGED_SESSION = "stagedSession";
    private static final String ATTR_STAGED_SESSION_ERROR_CODE = "errorCode";
    private static final String ATTR_STAGED_SESSION_ERROR_MESSAGE = "errorMessage";
    private static final String ATTR_UPDATED_MILLIS = "updatedMillis";
    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_VOLUME_UUID = "volumeUuid";
    private static final boolean LOGD = true;
    private static final int MSG_COMMIT = 1;
    private static final int MSG_ON_PACKAGE_INSTALLED = 2;
    private static final String PROPERTY_NAME_INHERIT_NATIVE = "pi.inherit_native_on_dont_kill";
    private static final String REMOVE_SPLIT_MARKER_EXTENSION = ".removed";
    private static final String TAG = "PackageInstallerSession";
    static final String TAG_CHILD_SESSION = "childSession";
    private static final String TAG_GRANTED_RUNTIME_PERMISSION = "granted-runtime-permission";
    static final String TAG_SESSION = "session";
    private static final String TAG_WHITELISTED_RESTRICTED_PERMISSION = "whitelisted-restricted-permission";
    final long createdMillis;
    private final PackageInstallerService.InternalCallback mCallback;
    @GuardedBy({"mLock"})
    private boolean mCommitted;
    private final Context mContext;
    @GuardedBy({"mLock"})
    private String mFinalMessage;
    @GuardedBy({"mLock"})
    private int mFinalStatus;
    private final Handler mHandler;
    @GuardedBy({"mLock"})
    private File mInheritedFilesBase;
    @GuardedBy({"mLock"})
    private String mInstallerPackageName;
    @GuardedBy({"mLock"})
    private int mInstallerUid;
    private final int mOriginalInstallerUid;
    @GuardedBy({"mLock"})
    private String mPackageName;
    @GuardedBy({"mLock"})
    private int mParentSessionId;
    private final PackageManagerService mPm;
    @GuardedBy({"mLock"})
    private boolean mPrepared;
    @GuardedBy({"mLock"})
    private IPackageInstallObserver2 mRemoteObserver;
    @GuardedBy({"mLock"})
    private File mResolvedBaseFile;
    @GuardedBy({"mLock"})
    private File mResolvedStageDir;
    private final PackageSessionProvider mSessionProvider;
    @GuardedBy({"mLock"})
    private boolean mShouldBeSealed;
    @GuardedBy({"mLock"})
    private PackageParser.SigningDetails mSigningDetails;
    @GuardedBy({"mLock"})
    private boolean mStagedSessionApplied;
    @GuardedBy({"mLock"})
    private int mStagedSessionErrorCode;
    @GuardedBy({"mLock"})
    private String mStagedSessionErrorMessage;
    @GuardedBy({"mLock"})
    private boolean mStagedSessionFailed;
    @GuardedBy({"mLock"})
    private boolean mStagedSessionReady;
    private final StagingManager mStagingManager;
    @GuardedBy({"mLock"})
    private boolean mVerityFound;
    @GuardedBy({"mLock"})
    private long mVersionCode;
    final PackageInstaller.SessionParams params;
    final int sessionId;
    final String stageCid;
    final File stageDir;
    @GuardedBy({"mLock"})
    private long updatedMillis;
    final int userId;
    private static final int[] EMPTY_CHILD_SESSION_ARRAY = new int[0];
    private static final FileFilter sAddedFilter = new FileFilter() { // from class: com.android.server.pm.PackageInstallerSession.1
        @Override // java.io.FileFilter
        public boolean accept(File file) {
            return (file.isDirectory() || file.getName().endsWith(PackageInstallerSession.REMOVE_SPLIT_MARKER_EXTENSION) || DexMetadataHelper.isDexMetadataFile(file) || VerityUtils.isFsveritySignatureFile(file)) ? false : true;
        }
    };
    private static final FileFilter sRemovedFilter = new FileFilter() { // from class: com.android.server.pm.PackageInstallerSession.2
        @Override // java.io.FileFilter
        public boolean accept(File file) {
            return !file.isDirectory() && file.getName().endsWith(PackageInstallerSession.REMOVE_SPLIT_MARKER_EXTENSION);
        }
    };
    private final AtomicInteger mActiveCount = new AtomicInteger();
    private final Object mLock = new Object();
    @GuardedBy({"mLock"})
    private float mClientProgress = 0.0f;
    @GuardedBy({"mLock"})
    private float mInternalProgress = 0.0f;
    @GuardedBy({"mLock"})
    private float mProgress = 0.0f;
    @GuardedBy({"mLock"})
    private float mReportedProgress = -1.0f;
    @GuardedBy({"mLock"})
    private boolean mSealed = false;
    @GuardedBy({"mLock"})
    private boolean mRelinquished = false;
    @GuardedBy({"mLock"})
    private boolean mDestroyed = false;
    @GuardedBy({"mLock"})
    private boolean mPermissionsManuallyAccepted = false;
    @GuardedBy({"mLock"})
    private final ArrayList<RevocableFileDescriptor> mFds = new ArrayList<>();
    @GuardedBy({"mLock"})
    private final ArrayList<FileBridge> mBridges = new ArrayList<>();
    @GuardedBy({"mLock"})
    private SparseIntArray mChildSessionIds = new SparseIntArray();
    @GuardedBy({"mLock"})
    private final List<File> mResolvedStagedFiles = new ArrayList();
    @GuardedBy({"mLock"})
    private final List<File> mResolvedInheritedFiles = new ArrayList();
    @GuardedBy({"mLock"})
    private final List<String> mResolvedInstructionSets = new ArrayList();
    @GuardedBy({"mLock"})
    private final List<String> mResolvedNativeLibPaths = new ArrayList();
    private final Handler.Callback mHandlerCallback = new Handler.Callback() { // from class: com.android.server.pm.PackageInstallerSession.3
        @Override // android.os.Handler.Callback
        public boolean handleMessage(Message msg) {
            int i = msg.what;
            if (i == 1) {
                PackageInstallerSession.this.handleCommit();
            } else if (i == 2) {
                SomeArgs args = (SomeArgs) msg.obj;
                String packageName = (String) args.arg1;
                String message = (String) args.arg2;
                Bundle extras = (Bundle) args.arg3;
                IPackageInstallObserver2 observer = (IPackageInstallObserver2) args.arg4;
                int returnCode = args.argi1;
                args.recycle();
                try {
                    observer.onPackageInstalled(packageName, returnCode, message, extras);
                } catch (RemoteException e) {
                }
            }
            return true;
        }
    };

    @GuardedBy({"mLock"})
    private boolean isInstallerDeviceOwnerOrAffiliatedProfileOwnerLocked() {
        DevicePolicyManagerInternal dpmi;
        return this.userId == UserHandle.getUserId(this.mInstallerUid) && (dpmi = (DevicePolicyManagerInternal) LocalServices.getService(DevicePolicyManagerInternal.class)) != null && dpmi.canSilentlyInstallPackage(this.mInstallerPackageName, this.mInstallerUid);
    }

    @GuardedBy({"mLock"})
    private boolean needToAskForPermissionsLocked() {
        if (this.mPermissionsManuallyAccepted) {
            return false;
        }
        boolean isInstallPermissionGranted = this.mPm.checkUidPermission("android.permission.INSTALL_PACKAGES", this.mInstallerUid) == 0;
        boolean isSelfUpdatePermissionGranted = this.mPm.checkUidPermission("android.permission.INSTALL_SELF_UPDATES", this.mInstallerUid) == 0;
        boolean isUpdatePermissionGranted = this.mPm.checkUidPermission("android.permission.INSTALL_PACKAGE_UPDATES", this.mInstallerUid) == 0;
        int targetPackageUid = this.mPm.getPackageUid(this.mPackageName, 0, this.userId);
        boolean isPermissionGranted = isInstallPermissionGranted || (isUpdatePermissionGranted && targetPackageUid != -1) || (isSelfUpdatePermissionGranted && targetPackageUid == this.mInstallerUid);
        boolean isInstallerRoot = this.mInstallerUid == 0;
        boolean isInstallerSystem = this.mInstallerUid == 1000;
        boolean forcePermissionPrompt = (this.params.installFlags & 1024) != 0;
        return forcePermissionPrompt || !(isPermissionGranted || isInstallerRoot || isInstallerSystem || isInstallerDeviceOwnerOrAffiliatedProfileOwnerLocked());
    }

    public PackageInstallerSession(PackageInstallerService.InternalCallback callback, Context context, PackageManagerService pm, PackageSessionProvider sessionProvider, Looper looper, StagingManager stagingManager, int sessionId, int userId, String installerPackageName, int installerUid, PackageInstaller.SessionParams params, long createdMillis, File stageDir, String stageCid, boolean prepared, boolean committed, boolean sealed, int[] childSessionIds, int parentSessionId, boolean isReady, boolean isFailed, boolean isApplied, int stagedSessionErrorCode, String stagedSessionErrorMessage) {
        boolean z;
        int[] iArr = childSessionIds;
        this.mPrepared = false;
        this.mShouldBeSealed = false;
        this.mCommitted = false;
        this.mStagedSessionErrorCode = 0;
        this.mCallback = callback;
        this.mContext = context;
        this.mPm = pm;
        this.mSessionProvider = sessionProvider;
        this.mHandler = new Handler(looper, this.mHandlerCallback);
        this.mStagingManager = stagingManager;
        this.sessionId = sessionId;
        this.userId = userId;
        this.mOriginalInstallerUid = installerUid;
        this.mInstallerPackageName = installerPackageName;
        this.mInstallerUid = installerUid;
        this.params = params;
        this.createdMillis = createdMillis;
        this.updatedMillis = createdMillis;
        this.stageDir = stageDir;
        this.stageCid = stageCid;
        this.mShouldBeSealed = sealed;
        if (iArr == null) {
            z = false;
        } else {
            int i = 0;
            for (int length = iArr.length; i < length; length = length) {
                int childSessionId = iArr[i];
                this.mChildSessionIds.put(childSessionId, 0);
                i++;
                iArr = childSessionIds;
            }
            z = false;
        }
        this.mParentSessionId = parentSessionId;
        if (!params.isMultiPackage) {
            if ((stageDir == null ? true : z) == (stageCid == null ? true : z)) {
                throw new IllegalArgumentException("Exactly one of stageDir or stageCid stage must be set");
            }
        }
        this.mPrepared = prepared;
        this.mCommitted = committed;
        this.mStagedSessionReady = isReady;
        this.mStagedSessionFailed = isFailed;
        this.mStagedSessionApplied = isApplied;
        this.mStagedSessionErrorCode = stagedSessionErrorCode;
        this.mStagedSessionErrorMessage = stagedSessionErrorMessage != null ? stagedSessionErrorMessage : "";
    }

    private boolean shouldScrubData(int callingUid) {
        return callingUid >= 10000 && getInstallerUid() != callingUid;
    }

    public PackageInstaller.SessionInfo generateInfoForCaller(boolean includeIcon, int callingUid) {
        return generateInfoInternal(includeIcon, shouldScrubData(callingUid));
    }

    public PackageInstaller.SessionInfo generateInfoScrubbed(boolean includeIcon) {
        return generateInfoInternal(includeIcon, true);
    }

    private PackageInstaller.SessionInfo generateInfoInternal(boolean includeIcon, boolean scrubData) {
        PackageInstaller.SessionInfo info = new PackageInstaller.SessionInfo();
        synchronized (this.mLock) {
            info.sessionId = this.sessionId;
            info.userId = this.userId;
            info.installerPackageName = this.mInstallerPackageName;
            info.resolvedBaseCodePath = this.mResolvedBaseFile != null ? this.mResolvedBaseFile.getAbsolutePath() : null;
            info.progress = this.mProgress;
            info.sealed = this.mSealed;
            info.isCommitted = this.mCommitted;
            info.active = this.mActiveCount.get() > 0;
            info.mode = this.params.mode;
            info.installReason = this.params.installReason;
            info.sizeBytes = this.params.sizeBytes;
            info.appPackageName = this.params.appPackageName;
            if (includeIcon) {
                info.appIcon = this.params.appIcon;
            }
            info.appLabel = this.params.appLabel;
            info.installLocation = this.params.installLocation;
            if (!scrubData) {
                info.originatingUri = this.params.originatingUri;
            }
            info.originatingUid = this.params.originatingUid;
            if (!scrubData) {
                info.referrerUri = this.params.referrerUri;
            }
            info.grantedRuntimePermissions = this.params.grantedRuntimePermissions;
            info.whitelistedRestrictedPermissions = this.params.whitelistedRestrictedPermissions;
            info.installFlags = this.params.installFlags;
            info.isMultiPackage = this.params.isMultiPackage;
            info.isStaged = this.params.isStaged;
            info.parentSessionId = this.mParentSessionId;
            info.childSessionIds = this.mChildSessionIds.copyKeys();
            if (info.childSessionIds == null) {
                info.childSessionIds = EMPTY_CHILD_SESSION_ARRAY;
            }
            info.isStagedSessionApplied = this.mStagedSessionApplied;
            info.isStagedSessionReady = this.mStagedSessionReady;
            info.isStagedSessionFailed = this.mStagedSessionFailed;
            info.setStagedSessionErrorCode(this.mStagedSessionErrorCode, this.mStagedSessionErrorMessage);
            info.updatedMillis = this.updatedMillis;
        }
        return info;
    }

    public boolean isPrepared() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mPrepared;
        }
        return z;
    }

    public boolean isSealed() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mSealed;
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isCommitted() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mCommitted;
        }
        return z;
    }

    public boolean isStagedAndInTerminalState() {
        boolean z;
        synchronized (this.mLock) {
            z = this.params.isStaged && (this.mStagedSessionApplied || this.mStagedSessionFailed);
        }
        return z;
    }

    @GuardedBy({"mLock"})
    private void assertPreparedAndNotSealedLocked(String cookie) {
        assertPreparedAndNotCommittedOrDestroyedLocked(cookie);
        if (this.mSealed) {
            throw new SecurityException(cookie + " not allowed after sealing");
        }
    }

    @GuardedBy({"mLock"})
    private void assertPreparedAndNotCommittedOrDestroyedLocked(String cookie) {
        assertPreparedAndNotDestroyedLocked(cookie);
        if (this.mCommitted) {
            throw new SecurityException(cookie + " not allowed after commit");
        }
    }

    @GuardedBy({"mLock"})
    private void assertPreparedAndNotDestroyedLocked(String cookie) {
        if (!this.mPrepared) {
            throw new IllegalStateException(cookie + " before prepared");
        } else if (this.mDestroyed) {
            throw new SecurityException(cookie + " not allowed after destruction");
        }
    }

    @GuardedBy({"mLock"})
    private File resolveStageDirLocked() throws IOException {
        if (this.mResolvedStageDir == null) {
            File file = this.stageDir;
            if (file != null) {
                this.mResolvedStageDir = file;
            } else {
                throw new IOException("Missing stageDir");
            }
        }
        return this.mResolvedStageDir;
    }

    public void setClientProgress(float progress) {
        synchronized (this.mLock) {
            assertCallerIsOwnerOrRootLocked();
            boolean forcePublish = this.mClientProgress == 0.0f;
            this.mClientProgress = progress;
            computeProgressLocked(forcePublish);
        }
    }

    public void addClientProgress(float progress) {
        synchronized (this.mLock) {
            assertCallerIsOwnerOrRootLocked();
            setClientProgress(this.mClientProgress + progress);
        }
    }

    @GuardedBy({"mLock"})
    private void computeProgressLocked(boolean forcePublish) {
        this.mProgress = MathUtils.constrain(this.mClientProgress * 0.8f, 0.0f, 0.8f) + MathUtils.constrain(this.mInternalProgress * 0.2f, 0.0f, 0.2f);
        if (forcePublish || Math.abs(this.mProgress - this.mReportedProgress) >= 0.01d) {
            float f = this.mProgress;
            this.mReportedProgress = f;
            this.mCallback.onSessionProgressChanged(this, f);
        }
    }

    public String[] getNames() {
        String[] list;
        synchronized (this.mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotCommittedOrDestroyedLocked("getNames");
            try {
                list = resolveStageDirLocked().list();
            } catch (IOException e) {
                throw ExceptionUtils.wrap(e);
            }
        }
        return list;
    }

    public void removeSplit(String splitName) {
        if (TextUtils.isEmpty(this.params.appPackageName)) {
            throw new IllegalStateException("Must specify package name to remove a split");
        }
        synchronized (this.mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotCommittedOrDestroyedLocked("removeSplit");
            try {
                createRemoveSplitMarkerLocked(splitName);
            } catch (IOException e) {
                throw ExceptionUtils.wrap(e);
            }
        }
    }

    private void createRemoveSplitMarkerLocked(String splitName) throws IOException {
        try {
            String markerName = splitName + REMOVE_SPLIT_MARKER_EXTENSION;
            if (!FileUtils.isValidExtFilename(markerName)) {
                throw new IllegalArgumentException("Invalid marker: " + markerName);
            }
            File target = new File(resolveStageDirLocked(), markerName);
            target.createNewFile();
            Os.chmod(target.getAbsolutePath(), 0);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    public ParcelFileDescriptor openWrite(String name, long offsetBytes, long lengthBytes) {
        try {
            return doWriteInternal(name, offsetBytes, lengthBytes, null);
        } catch (IOException e) {
            throw ExceptionUtils.wrap(e);
        }
    }

    public void write(String name, long offsetBytes, long lengthBytes, ParcelFileDescriptor fd) {
        try {
            doWriteInternal(name, offsetBytes, lengthBytes, fd);
        } catch (IOException e) {
            throw ExceptionUtils.wrap(e);
        }
    }

    private ParcelFileDescriptor doWriteInternal(String name, long offsetBytes, long lengthBytes, ParcelFileDescriptor incomingFd) throws IOException {
        RevocableFileDescriptor fd;
        FileBridge bridge;
        File stageDir;
        FileDescriptor targetFd;
        synchronized (this.mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotSealedLocked("openWrite");
            if (PackageInstaller.ENABLE_REVOCABLE_FD) {
                RevocableFileDescriptor fd2 = new RevocableFileDescriptor();
                this.mFds.add(fd2);
                fd = fd2;
                bridge = null;
            } else {
                FileBridge bridge2 = new FileBridge();
                this.mBridges.add(bridge2);
                fd = null;
                bridge = bridge2;
            }
            stageDir = resolveStageDirLocked();
        }
        try {
            try {
                if (!FileUtils.isValidExtFilename(name)) {
                    throw new IllegalArgumentException("Invalid name: " + name);
                }
                long identity = Binder.clearCallingIdentity();
                File target = new File(stageDir, name);
                Binder.restoreCallingIdentity(identity);
                FileDescriptor targetFd2 = Os.open(target.getAbsolutePath(), OsConstants.O_CREAT | OsConstants.O_WRONLY, 420);
                Os.chmod(target.getAbsolutePath(), 420);
                if (stageDir != null && lengthBytes > 0) {
                    try {
                        ((StorageManager) this.mContext.getSystemService(StorageManager.class)).allocateBytes(targetFd2, lengthBytes, PackageHelper.translateAllocateFlags(this.params.installFlags));
                    } catch (ErrnoException e) {
                        e = e;
                        throw e.rethrowAsIOException();
                    }
                }
                if (offsetBytes > 0) {
                    Os.lseek(targetFd2, offsetBytes, OsConstants.SEEK_SET);
                }
                if (incomingFd == null) {
                    if (PackageInstaller.ENABLE_REVOCABLE_FD) {
                        fd.init(this.mContext, targetFd2);
                        return fd.getRevocableFileDescriptor();
                    }
                    bridge.setTargetFile(targetFd2);
                    bridge.start();
                    return new ParcelFileDescriptor(bridge.getClientSocket());
                }
                int callingUid = Binder.getCallingUid();
                if (callingUid != 0 && callingUid != 1000 && callingUid != 2000) {
                    throw new SecurityException("Reverse mode only supported from shell or system");
                }
                try {
                    final Int64Ref last = new Int64Ref(0L);
                    targetFd = targetFd2;
                    try {
                        FileUtils.copy(incomingFd.getFileDescriptor(), targetFd2, lengthBytes, null, new Executor() { // from class: com.android.server.pm.-$$Lambda$_14QHG018Z6p13d3hzJuGTWnNeo
                            @Override // java.util.concurrent.Executor
                            public final void execute(Runnable runnable) {
                                runnable.run();
                            }
                        }, new FileUtils.ProgressListener() { // from class: com.android.server.pm.-$$Lambda$PackageInstallerSession$0Oqu1oanLjaOBEcFPtJVCRQ0lHs
                            @Override // android.os.FileUtils.ProgressListener
                            public final void onProgress(long j) {
                                PackageInstallerSession.this.lambda$doWriteInternal$0$PackageInstallerSession(last, j);
                            }
                        });
                        IoUtils.closeQuietly(targetFd);
                        IoUtils.closeQuietly(incomingFd);
                        synchronized (this.mLock) {
                            try {
                                if (PackageInstaller.ENABLE_REVOCABLE_FD) {
                                    this.mFds.remove(fd);
                                } else {
                                    bridge.forceClose();
                                    this.mBridges.remove(bridge);
                                }
                            } catch (Throwable th) {
                                th = th;
                                throw th;
                            }
                        }
                        return null;
                    } catch (Throwable th2) {
                        th = th2;
                        IoUtils.closeQuietly(targetFd);
                        IoUtils.closeQuietly(incomingFd);
                        synchronized (this.mLock) {
                            try {
                                if (PackageInstaller.ENABLE_REVOCABLE_FD) {
                                    this.mFds.remove(fd);
                                } else {
                                    bridge.forceClose();
                                    this.mBridges.remove(bridge);
                                }
                            } catch (Throwable th3) {
                                th = th3;
                            }
                        }
                        throw th;
                    }
                } catch (Throwable th4) {
                    th = th4;
                    targetFd = targetFd2;
                }
            } catch (ErrnoException e2) {
                e = e2;
            }
        } catch (ErrnoException e3) {
            e = e3;
        }
    }

    public /* synthetic */ void lambda$doWriteInternal$0$PackageInstallerSession(Int64Ref last, long progress) {
        if (this.params.sizeBytes > 0) {
            long delta = progress - last.value;
            last.value = progress;
            addClientProgress(((float) delta) / ((float) this.params.sizeBytes));
        }
    }

    public ParcelFileDescriptor openRead(String name) {
        ParcelFileDescriptor openReadInternalLocked;
        synchronized (this.mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotCommittedOrDestroyedLocked("openRead");
            try {
                openReadInternalLocked = openReadInternalLocked(name);
            } catch (IOException e) {
                throw ExceptionUtils.wrap(e);
            }
        }
        return openReadInternalLocked;
    }

    private ParcelFileDescriptor openReadInternalLocked(String name) throws IOException {
        try {
            if (!FileUtils.isValidExtFilename(name)) {
                throw new IllegalArgumentException("Invalid name: " + name);
            }
            File target = new File(resolveStageDirLocked(), name);
            FileDescriptor targetFd = Os.open(target.getAbsolutePath(), OsConstants.O_RDONLY, 0);
            return new ParcelFileDescriptor(targetFd);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    @GuardedBy({"mLock"})
    private void assertCallerIsOwnerOrRootLocked() {
        int callingUid = Binder.getCallingUid();
        if (callingUid != 0 && callingUid != this.mInstallerUid) {
            throw new SecurityException("Session does not belong to uid " + callingUid);
        }
    }

    @GuardedBy({"mLock"})
    private void assertNoWriteFileTransfersOpenLocked() {
        Iterator<RevocableFileDescriptor> it = this.mFds.iterator();
        while (it.hasNext()) {
            RevocableFileDescriptor fd = it.next();
            if (!fd.isRevoked()) {
                throw new SecurityException("Files still open");
            }
        }
        Iterator<FileBridge> it2 = this.mBridges.iterator();
        while (it2.hasNext()) {
            FileBridge bridge = it2.next();
            if (!bridge.isClosed()) {
                throw new SecurityException("Files still open");
            }
        }
    }

    public void commit(IntentSender statusReceiver, boolean forTransfer) {
        if (hasParentSessionId()) {
            throw new IllegalStateException("Session " + this.sessionId + " is a child of multi-package session " + this.mParentSessionId + " and may not be committed directly.");
        } else if (markAsCommitted(statusReceiver, forTransfer)) {
            if (isMultiPackage()) {
                SparseIntArray remainingSessions = this.mChildSessionIds.clone();
                IntentSender childIntentSender = new ChildStatusIntentReceiver(remainingSessions, statusReceiver).getIntentSender();
                RuntimeException commitException = null;
                boolean commitFailed = false;
                for (int i = this.mChildSessionIds.size() - 1; i >= 0; i--) {
                    int childSessionId = this.mChildSessionIds.keyAt(i);
                    try {
                        if (!this.mSessionProvider.getSession(childSessionId).markAsCommitted(childIntentSender, forTransfer)) {
                            commitFailed = true;
                        }
                    } catch (RuntimeException e) {
                        commitException = e;
                    }
                }
                if (commitException != null) {
                    throw commitException;
                }
                if (commitFailed) {
                    return;
                }
            }
            this.mHandler.obtainMessage(1).sendToTarget();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class ChildStatusIntentReceiver {
        private final SparseIntArray mChildSessionsRemaining;
        private final IIntentSender.Stub mLocalSender;
        private final IntentSender mStatusReceiver;

        private ChildStatusIntentReceiver(SparseIntArray remainingSessions, IntentSender statusReceiver) {
            this.mLocalSender = new IIntentSender.Stub() { // from class: com.android.server.pm.PackageInstallerSession.ChildStatusIntentReceiver.1
                public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken, IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                    ChildStatusIntentReceiver.this.statusUpdate(intent);
                }
            };
            this.mChildSessionsRemaining = remainingSessions;
            this.mStatusReceiver = statusReceiver;
        }

        public IntentSender getIntentSender() {
            return new IntentSender(this.mLocalSender);
        }

        public void statusUpdate(final Intent intent) {
            PackageInstallerSession.this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageInstallerSession$ChildStatusIntentReceiver$CIWymiEKCzNknV3an6tFtcz5-Mc
                @Override // java.lang.Runnable
                public final void run() {
                    PackageInstallerSession.ChildStatusIntentReceiver.this.lambda$statusUpdate$0$PackageInstallerSession$ChildStatusIntentReceiver(intent);
                }
            });
        }

        public /* synthetic */ void lambda$statusUpdate$0$PackageInstallerSession$ChildStatusIntentReceiver(Intent intent) {
            if (this.mChildSessionsRemaining.size() == 0) {
                return;
            }
            int sessionId = intent.getIntExtra("android.content.pm.extra.SESSION_ID", 0);
            int status = intent.getIntExtra("android.content.pm.extra.STATUS", 1);
            int sessionIndex = this.mChildSessionsRemaining.indexOfKey(sessionId);
            if (status == 0) {
                this.mChildSessionsRemaining.removeAt(sessionIndex);
                if (this.mChildSessionsRemaining.size() == 0) {
                    try {
                        intent.putExtra("android.content.pm.extra.SESSION_ID", PackageInstallerSession.this.sessionId);
                        this.mStatusReceiver.sendIntent(PackageInstallerSession.this.mContext, 0, intent, null, null);
                    } catch (IntentSender.SendIntentException e) {
                    }
                }
            } else if (-1 == status) {
                try {
                    this.mStatusReceiver.sendIntent(PackageInstallerSession.this.mContext, 0, intent, null, null);
                } catch (IntentSender.SendIntentException e2) {
                }
            } else {
                intent.putExtra("android.content.pm.extra.SESSION_ID", PackageInstallerSession.this.sessionId);
                this.mChildSessionsRemaining.clear();
                try {
                    this.mStatusReceiver.sendIntent(PackageInstallerSession.this.mContext, 0, intent, null, null);
                } catch (IntentSender.SendIntentException e3) {
                }
            }
        }
    }

    public boolean markAsCommitted(IntentSender statusReceiver, boolean forTransfer) {
        Preconditions.checkNotNull(statusReceiver);
        List<PackageInstallerSession> childSessions = getChildSessions();
        synchronized (this.mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotDestroyedLocked("commit");
            PackageInstallerService.PackageInstallObserverAdapter adapter = new PackageInstallerService.PackageInstallObserverAdapter(this.mContext, statusReceiver, this.sessionId, isInstallerDeviceOwnerOrAffiliatedProfileOwnerLocked(), this.userId);
            this.mRemoteObserver = adapter.getBinder();
            if (forTransfer) {
                this.mContext.enforceCallingOrSelfPermission("android.permission.INSTALL_PACKAGES", null);
                if (this.mInstallerUid == this.mOriginalInstallerUid) {
                    throw new IllegalArgumentException("Session has not been transferred");
                }
            } else if (this.mInstallerUid != this.mOriginalInstallerUid) {
                throw new IllegalArgumentException("Session has been transferred");
            }
            if (this.mCommitted) {
                return true;
            }
            boolean wasSealed = this.mSealed;
            if (!this.mSealed) {
                try {
                    sealAndValidateLocked(childSessions);
                } catch (PackageManagerException e) {
                    destroyInternal();
                    dispatchSessionFinished(e.error, ExceptionUtils.getCompleteMessage(e), null);
                    return false;
                } catch (IOException e2) {
                    throw new IllegalArgumentException(e2);
                }
            }
            this.mClientProgress = 1.0f;
            computeProgressLocked(true);
            this.mActiveCount.incrementAndGet();
            this.mCommitted = true;
            if (!wasSealed) {
                this.mCallback.onSessionSealedBlocking(this);
            }
            return true;
        }
    }

    private List<PackageInstallerSession> getChildSessions() {
        List<PackageInstallerSession> childSessions = null;
        if (isMultiPackage()) {
            int[] childSessionIds = getChildSessionIds();
            childSessions = new ArrayList<>(childSessionIds.length);
            for (int childSessionId : childSessionIds) {
                childSessions.add(this.mSessionProvider.getSession(childSessionId));
            }
        }
        return childSessions;
    }

    @GuardedBy({"mLock"})
    private void assertMultiPackageConsistencyLocked(List<PackageInstallerSession> childSessions) throws PackageManagerException {
        for (PackageInstallerSession childSession : childSessions) {
            if (childSession != null) {
                assertConsistencyWithLocked(childSession);
            }
        }
    }

    @GuardedBy({"mLock"})
    private void assertConsistencyWithLocked(PackageInstallerSession other) throws PackageManagerException {
        if (this.params.isStaged != other.params.isStaged) {
            throw new PackageManagerException(-120, "Multipackage Inconsistency: session " + other.sessionId + " and session " + this.sessionId + " have inconsistent staged settings");
        } else if (this.params.getEnableRollback() != other.params.getEnableRollback()) {
            throw new PackageManagerException(-120, "Multipackage Inconsistency: session " + other.sessionId + " and session " + this.sessionId + " have inconsistent rollback settings");
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:12:0x0027, code lost:
        if (r6.mParentSessionId != r3) goto L12;
     */
    @com.android.internal.annotations.GuardedBy({"mLock"})
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void sealAndValidateLocked(java.util.List<com.android.server.pm.PackageInstallerSession> r7) throws com.android.server.pm.PackageManagerException, java.io.IOException {
        /*
            r6 = this;
            r6.assertNoWriteFileTransfersOpenLocked()
            java.lang.String r0 = "sealing of session"
            r6.assertPreparedAndNotDestroyedLocked(r0)
            r0 = 1
            r6.mSealed = r0
            if (r7 == 0) goto L11
            r6.assertMultiPackageConsistencyLocked(r7)
        L11:
            android.content.pm.PackageInstaller$SessionParams r1 = r6.params
            boolean r1 = r1.isStaged
            if (r1 == 0) goto L4a
            com.android.server.pm.StagingManager r1 = r6.mStagingManager
            com.android.server.pm.PackageInstallerSession r1 = r1.getActiveSession()
            if (r1 == 0) goto L2a
            int r2 = r6.sessionId
            int r3 = r1.sessionId
            if (r2 == r3) goto L2a
            int r2 = r6.mParentSessionId
            if (r2 == r3) goto L2a
            goto L2b
        L2a:
            r0 = 0
        L2b:
            if (r0 != 0) goto L2e
            goto L4a
        L2e:
            com.android.server.pm.PackageManagerException r2 = new com.android.server.pm.PackageManagerException
            r3 = -119(0xffffffffffffff89, float:NaN)
            java.lang.StringBuilder r4 = new java.lang.StringBuilder
            r4.<init>()
            java.lang.String r5 = "There is already in-progress committed staged session "
            r4.append(r5)
            int r5 = r1.sessionId
            r4.append(r5)
            java.lang.String r4 = r4.toString()
            r5 = 0
            r2.<init>(r3, r4, r5)
            throw r2
        L4a:
            android.content.pm.PackageInstaller$SessionParams r0 = r6.params
            boolean r0 = r0.isMultiPackage
            if (r0 != 0) goto L7c
            com.android.server.pm.PackageManagerService r0 = r6.mPm
            android.content.pm.PackageInstaller$SessionParams r1 = r6.params
            java.lang.String r1 = r1.appPackageName
            r2 = 67108928(0x4000040, float:1.5046442E-36)
            int r3 = r6.userId
            android.content.pm.PackageInfo r0 = r0.getPackageInfo(r1, r2, r3)
            r6.resolveStageDirLocked()
            android.content.pm.PackageInstaller$SessionParams r1 = r6.params     // Catch: java.lang.Throwable -> L73 com.android.server.pm.PackageManagerException -> L7a
            int r1 = r1.installFlags     // Catch: java.lang.Throwable -> L73 com.android.server.pm.PackageManagerException -> L7a
            r2 = 131072(0x20000, float:1.83671E-40)
            r1 = r1 & r2
            if (r1 == 0) goto L6f
            r6.validateApexInstallLocked()     // Catch: java.lang.Throwable -> L73 com.android.server.pm.PackageManagerException -> L7a
            goto L72
        L6f:
            r6.validateApkInstallLocked(r0)     // Catch: java.lang.Throwable -> L73 com.android.server.pm.PackageManagerException -> L7a
        L72:
            goto L7c
        L73:
            r1 = move-exception
            com.android.server.pm.PackageManagerException r2 = new com.android.server.pm.PackageManagerException
            r2.<init>(r1)
            throw r2
        L7a:
            r1 = move-exception
            throw r1
        L7c:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageInstallerSession.sealAndValidateLocked(java.util.List):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sealAndValidateIfNecessary() {
        synchronized (this.mLock) {
            if (this.mShouldBeSealed && !isStagedAndInTerminalState()) {
                List<PackageInstallerSession> childSessions = getChildSessions();
                synchronized (this.mLock) {
                    try {
                        sealAndValidateLocked(childSessions);
                    } catch (PackageManagerException e) {
                        Slog.e(TAG, "Package not valid", e);
                        destroyInternal();
                        dispatchSessionFinished(e.error, ExceptionUtils.getCompleteMessage(e), null);
                    } catch (IOException e2) {
                        throw new IllegalStateException(e2);
                    }
                }
            }
        }
    }

    public void markUpdated() {
        synchronized (this.mLock) {
            this.updatedMillis = System.currentTimeMillis();
        }
    }

    public void transfer(String packageName) {
        Preconditions.checkNotNull(packageName);
        ApplicationInfo newOwnerAppInfo = this.mPm.getApplicationInfo(packageName, 0, this.userId);
        if (newOwnerAppInfo == null) {
            throw new ParcelableException(new PackageManager.NameNotFoundException(packageName));
        }
        if (this.mPm.checkUidPermission("android.permission.INSTALL_PACKAGES", newOwnerAppInfo.uid) != 0) {
            throw new SecurityException("Destination package " + packageName + " does not have the android.permission.INSTALL_PACKAGES permission");
        } else if (!this.params.areHiddenOptionsSet()) {
            throw new SecurityException("Can only transfer sessions that use public options");
        } else {
            List<PackageInstallerSession> childSessions = getChildSessions();
            synchronized (this.mLock) {
                assertCallerIsOwnerOrRootLocked();
                assertPreparedAndNotSealedLocked("transfer");
                try {
                    try {
                        sealAndValidateLocked(childSessions);
                        if (!this.mPackageName.equals(this.mInstallerPackageName)) {
                            throw new SecurityException("Can only transfer sessions that update the original installer");
                        }
                        this.mInstallerPackageName = packageName;
                        this.mInstallerUid = newOwnerAppInfo.uid;
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                } catch (PackageManagerException e2) {
                    destroyInternal();
                    dispatchSessionFinished(e2.error, ExceptionUtils.getCompleteMessage(e2), null);
                    throw new IllegalArgumentException("Package is not valid", e2);
                }
            }
            this.mCallback.onSessionSealedBlocking(this);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleCommit() {
        if (isInstallerDeviceOwnerOrAffiliatedProfileOwnerLocked()) {
            DevicePolicyEventLogger.createEvent((int) HdmiCecKeycode.UI_BROADCAST_DIGITAL_CABLE).setAdmin(this.mInstallerPackageName).write();
        }
        if (this.params.isStaged) {
            this.mStagingManager.commitSession(this);
            destroyInternal();
            dispatchSessionFinished(1, "Session staged", null);
        } else if ((this.params.installFlags & 131072) != 0) {
            destroyInternal();
            dispatchSessionFinished(RequestStatus.SYS_ETIMEDOUT, "APEX packages can only be installed using staged sessions.", null);
        } else {
            List<PackageInstallerSession> childSessions = getChildSessions();
            try {
                synchronized (this.mLock) {
                    commitNonStagedLocked(childSessions);
                }
            } catch (PackageManagerException e) {
                String completeMsg = ExceptionUtils.getCompleteMessage(e);
                Slog.e(TAG, "Commit of session " + this.sessionId + " failed: " + completeMsg);
                destroyInternal();
                dispatchSessionFinished(e.error, completeMsg, null);
            }
        }
    }

    @GuardedBy({"mLock"})
    private void commitNonStagedLocked(List<PackageInstallerSession> childSessions) throws PackageManagerException {
        PackageManagerService.ActiveInstallSession committingSession = makeSessionActiveLocked();
        if (committingSession == null) {
            return;
        }
        if (isMultiPackage()) {
            List<PackageManagerService.ActiveInstallSession> activeChildSessions = new ArrayList<>(childSessions.size());
            boolean success = true;
            PackageManagerException failure = null;
            for (int i = 0; i < childSessions.size(); i++) {
                PackageInstallerSession session = childSessions.get(i);
                try {
                    PackageManagerService.ActiveInstallSession activeSession = session.makeSessionActiveLocked();
                    if (activeSession != null) {
                        activeChildSessions.add(activeSession);
                    }
                } catch (PackageManagerException e) {
                    failure = e;
                    success = false;
                }
            }
            if (!success) {
                try {
                    this.mRemoteObserver.onPackageInstalled((String) null, failure.error, failure.getLocalizedMessage(), (Bundle) null);
                    return;
                } catch (RemoteException e2) {
                    return;
                }
            }
            this.mPm.installStage(activeChildSessions);
            return;
        }
        this.mPm.installStage(committingSession);
    }

    @GuardedBy({"mLock"})
    private PackageManagerService.ActiveInstallSession makeSessionActiveLocked() throws PackageManagerException {
        IPackageInstallObserver2.Stub stub;
        UserHandle user;
        if (this.mRelinquished) {
            throw new PackageManagerException(RequestStatus.SYS_ETIMEDOUT, "Session relinquished");
        }
        if (this.mDestroyed) {
            throw new PackageManagerException(RequestStatus.SYS_ETIMEDOUT, "Session destroyed");
        }
        if (!this.mSealed) {
            throw new PackageManagerException(RequestStatus.SYS_ETIMEDOUT, "Session not sealed");
        }
        if ((this.params.installFlags & 131072) != 0) {
            stub = null;
        } else {
            if (!this.params.isMultiPackage) {
                Preconditions.checkNotNull(this.mPackageName);
                Preconditions.checkNotNull(this.mSigningDetails);
                Preconditions.checkNotNull(this.mResolvedBaseFile);
                if (needToAskForPermissionsLocked()) {
                    Intent intent = new Intent("android.content.pm.action.CONFIRM_INSTALL");
                    intent.setPackage(this.mPm.getPackageInstallerPackageName());
                    intent.putExtra("android.content.pm.extra.SESSION_ID", this.sessionId);
                    try {
                        this.mRemoteObserver.onUserActionRequired(intent);
                    } catch (RemoteException e) {
                    }
                    closeInternal(false);
                    return null;
                }
                if (this.params.mode == 2) {
                    try {
                        List<File> fromFiles = this.mResolvedInheritedFiles;
                        File toDir = resolveStageDirLocked();
                        Slog.d(TAG, "Inherited files: " + this.mResolvedInheritedFiles);
                        if (!this.mResolvedInheritedFiles.isEmpty() && this.mInheritedFilesBase == null) {
                            throw new IllegalStateException("mInheritedFilesBase == null");
                        }
                        if (isLinkPossible(fromFiles, toDir)) {
                            if (!this.mResolvedInstructionSets.isEmpty()) {
                                File oatDir = new File(toDir, "oat");
                                createOatDirs(this.mResolvedInstructionSets, oatDir);
                            }
                            if (!this.mResolvedNativeLibPaths.isEmpty()) {
                                for (String libPath : this.mResolvedNativeLibPaths) {
                                    int splitIndex = libPath.lastIndexOf(47);
                                    if (splitIndex >= 0 && splitIndex < libPath.length() - 1) {
                                        String libDirPath = libPath.substring(1, splitIndex);
                                        File libDir = new File(toDir, libDirPath);
                                        if (!libDir.exists()) {
                                            NativeLibraryHelper.createNativeLibrarySubdir(libDir);
                                        }
                                        String archDirPath = libPath.substring(splitIndex + 1);
                                        NativeLibraryHelper.createNativeLibrarySubdir(new File(libDir, archDirPath));
                                    }
                                    Slog.e(TAG, "Skipping native library creation for linking due to invalid path: " + libPath);
                                }
                            }
                            linkFiles(fromFiles, toDir, this.mInheritedFilesBase);
                        } else {
                            copyFiles(fromFiles, toDir);
                        }
                    } catch (IOException e2) {
                        throw new PackageManagerException(-4, "Failed to inherit existing install", e2);
                    }
                }
                this.mInternalProgress = 0.5f;
                computeProgressLocked(true);
                extractNativeLibraries(this.mResolvedStageDir, this.params.abiOverride, mayInheritNativeLibs());
            }
            stub = new IPackageInstallObserver2.Stub() { // from class: com.android.server.pm.PackageInstallerSession.4
                public void onUserActionRequired(Intent intent2) {
                    throw new IllegalStateException();
                }

                public void onPackageInstalled(String basePackageName, int returnCode, String msg, Bundle extras) {
                    PackageInstallerSession.this.destroyInternal();
                    PackageInstallerSession.this.dispatchSessionFinished(returnCode, msg, extras);
                }
            };
        }
        if ((this.params.installFlags & 64) != 0) {
            user = UserHandle.ALL;
        } else {
            user = new UserHandle(this.userId);
        }
        this.mRelinquished = true;
        return new PackageManagerService.ActiveInstallSession(this.mPackageName, this.stageDir, stub, this.params, this.mInstallerPackageName, this.mInstallerUid, user, this.mSigningDetails);
    }

    private static void maybeRenameFile(File from, File to) throws PackageManagerException {
        if (!from.equals(to) && !from.renameTo(to)) {
            throw new PackageManagerException(RequestStatus.SYS_ETIMEDOUT, "Could not rename file " + from + " to " + to);
        }
    }

    private boolean mayInheritNativeLibs() {
        return SystemProperties.getBoolean(PROPERTY_NAME_INHERIT_NATIVE, true) && this.params.mode == 2 && (this.params.installFlags & 1) != 0;
    }

    @GuardedBy({"mLock"})
    private void validateApexInstallLocked() throws PackageManagerException {
        File[] addedFiles = this.mResolvedStageDir.listFiles(sAddedFilter);
        if (ArrayUtils.isEmpty(addedFiles)) {
            throw new PackageManagerException(-2, "No packages staged");
        }
        if (ArrayUtils.size(addedFiles) > 1) {
            throw new PackageManagerException(-2, "Too many files for apex install");
        }
        this.mResolvedBaseFile = addedFiles[0];
    }

    @GuardedBy({"mLock"})
    private void validateApkInstallLocked(PackageInfo pkgInfo) throws PackageManagerException {
        File[] libDirs;
        File[] removedFiles;
        List<File> libDirsToInherit;
        File[] fileArr;
        File[] removedFiles2;
        List<File> libDirsToInherit2;
        ApplicationInfo appInfo;
        List<String> removeSplitList;
        this.mPackageName = null;
        this.mVersionCode = -1L;
        this.mSigningDetails = PackageParser.SigningDetails.UNKNOWN;
        this.mResolvedBaseFile = null;
        this.mResolvedStagedFiles.clear();
        this.mResolvedInheritedFiles.clear();
        if (this.params.mode != 2 || (pkgInfo != null && pkgInfo.applicationInfo != null)) {
            this.mVerityFound = PackageManagerServiceUtils.isApkVerityEnabled() && this.params.mode == 2 && VerityUtils.hasFsverity(pkgInfo.applicationInfo.getBaseCodePath());
            try {
                resolveStageDirLocked();
                File[] removedFiles3 = this.mResolvedStageDir.listFiles(sRemovedFilter);
                List<String> arrayList = new ArrayList<>();
                if (!ArrayUtils.isEmpty(removedFiles3)) {
                    for (File removedFile : removedFiles3) {
                        String fileName = removedFile.getName();
                        arrayList.add(fileName.substring(0, fileName.length() - REMOVE_SPLIT_MARKER_EXTENSION.length()));
                    }
                }
                File[] addedFiles = this.mResolvedStageDir.listFiles(sAddedFilter);
                if (ArrayUtils.isEmpty(addedFiles) && arrayList.size() == 0) {
                    throw new PackageManagerException(-2, "No packages staged");
                }
                ArraySet<String> stagedSplits = new ArraySet<>();
                int length = addedFiles.length;
                PackageParser.ApkLite baseApk = null;
                int i = 0;
                while (i < length) {
                    File addedFile = addedFiles[i];
                    try {
                        PackageParser.ApkLite apk = PackageParser.parseApkLite(addedFile, 32);
                        if (!stagedSplits.add(apk.splitName)) {
                            throw new PackageManagerException(-2, "Split " + apk.splitName + " was defined multiple times");
                        }
                        if (this.mPackageName == null) {
                            this.mPackageName = apk.packageName;
                            removeSplitList = arrayList;
                            this.mVersionCode = apk.getLongVersionCode();
                        } else {
                            removeSplitList = arrayList;
                        }
                        if (this.mSigningDetails == PackageParser.SigningDetails.UNKNOWN) {
                            this.mSigningDetails = apk.signingDetails;
                        }
                        assertApkConsistentLocked(String.valueOf(addedFile), apk);
                        String targetName = apk.splitName == null ? "base.apk" : "split_" + apk.splitName + ".apk";
                        if (!FileUtils.isValidExtFilename(targetName)) {
                            throw new PackageManagerException(-2, "Invalid filename: " + targetName);
                        }
                        File targetFile = new File(this.mResolvedStageDir, targetName);
                        resolveAndStageFile(addedFile, targetFile);
                        if (apk.splitName == null) {
                            this.mResolvedBaseFile = targetFile;
                            baseApk = apk;
                        }
                        File dexMetadataFile = DexMetadataHelper.findDexMetadataForFile(addedFile);
                        if (dexMetadataFile != null) {
                            if (!FileUtils.isValidExtFilename(dexMetadataFile.getName())) {
                                throw new PackageManagerException(-2, "Invalid filename: " + dexMetadataFile);
                            }
                            File targetDexMetadataFile = new File(this.mResolvedStageDir, DexMetadataHelper.buildDexMetadataPathForApk(targetName));
                            resolveAndStageFile(dexMetadataFile, targetDexMetadataFile);
                        }
                        i++;
                        arrayList = removeSplitList;
                    } catch (PackageParser.PackageParserException e) {
                        throw PackageManagerException.from(e);
                    }
                }
                List<String> removeSplitList2 = arrayList;
                if (removeSplitList2.size() > 0) {
                    if (pkgInfo == null) {
                        throw new PackageManagerException(-2, "Missing existing base package for " + this.mPackageName);
                    }
                    for (String splitName : removeSplitList2) {
                        if (!ArrayUtils.contains(pkgInfo.splitNames, splitName)) {
                            throw new PackageManagerException(-2, "Split not found: " + splitName);
                        }
                    }
                    if (this.mPackageName == null) {
                        this.mPackageName = pkgInfo.packageName;
                        this.mVersionCode = pkgInfo.getLongVersionCode();
                    }
                    if (this.mSigningDetails == PackageParser.SigningDetails.UNKNOWN) {
                        try {
                            this.mSigningDetails = ApkSignatureVerifier.unsafeGetCertsWithoutVerification(pkgInfo.applicationInfo.sourceDir, 1);
                        } catch (PackageParser.PackageParserException e2) {
                            throw new PackageManagerException(-2, "Couldn't obtain signatures from base APK");
                        }
                    }
                }
                if (this.params.mode == 1) {
                    if (!stagedSplits.contains(null)) {
                        throw new PackageManagerException(-2, "Full install must include a base package");
                    }
                } else {
                    ApplicationInfo appInfo2 = pkgInfo.applicationInfo;
                    try {
                        PackageParser.PackageLite existing = PackageParser.parsePackageLite(new File(appInfo2.getCodePath()), 0);
                        PackageParser.ApkLite existingBase = PackageParser.parseApkLite(new File(appInfo2.getBaseCodePath()), 32);
                        assertApkConsistentLocked("Existing base", existingBase);
                        if (this.mResolvedBaseFile == null) {
                            this.mResolvedBaseFile = new File(appInfo2.getBaseCodePath());
                            resolveInheritedFile(this.mResolvedBaseFile);
                            File baseDexMetadataFile = DexMetadataHelper.findDexMetadataForFile(this.mResolvedBaseFile);
                            if (baseDexMetadataFile != null) {
                                resolveInheritedFile(baseDexMetadataFile);
                            }
                            baseApk = existingBase;
                        }
                        if (!ArrayUtils.isEmpty(existing.splitNames)) {
                            for (int i2 = 0; i2 < existing.splitNames.length; i2++) {
                                String splitName2 = existing.splitNames[i2];
                                File splitFile = new File(existing.splitCodePaths[i2]);
                                boolean splitRemoved = removeSplitList2.contains(splitName2);
                                if (!stagedSplits.contains(splitName2) && !splitRemoved) {
                                    resolveInheritedFile(splitFile);
                                    File splitDexMetadataFile = DexMetadataHelper.findDexMetadataForFile(splitFile);
                                    if (splitDexMetadataFile != null) {
                                        resolveInheritedFile(splitDexMetadataFile);
                                    }
                                }
                            }
                        }
                        File packageInstallDir = new File(appInfo2.getBaseCodePath()).getParentFile();
                        this.mInheritedFilesBase = packageInstallDir;
                        File oatDir = new File(packageInstallDir, "oat");
                        if (oatDir.exists()) {
                            File[] archSubdirs = oatDir.listFiles();
                            if (archSubdirs != null && archSubdirs.length > 0) {
                                String[] instructionSets = InstructionSets.getAllDexCodeInstructionSets();
                                int length2 = archSubdirs.length;
                                int i3 = 0;
                                while (i3 < length2) {
                                    File archSubDir = archSubdirs[i3];
                                    File[] archSubdirs2 = archSubdirs;
                                    if (!ArrayUtils.contains(instructionSets, archSubDir.getName())) {
                                        appInfo = appInfo2;
                                    } else {
                                        appInfo = appInfo2;
                                        this.mResolvedInstructionSets.add(archSubDir.getName());
                                        List<File> oatFiles = Arrays.asList(archSubDir.listFiles());
                                        if (!oatFiles.isEmpty()) {
                                            this.mResolvedInheritedFiles.addAll(oatFiles);
                                        }
                                    }
                                    i3++;
                                    archSubdirs = archSubdirs2;
                                    appInfo2 = appInfo;
                                }
                            }
                        }
                        if (mayInheritNativeLibs() && removeSplitList2.isEmpty()) {
                            File[] libDirs2 = {new File(packageInstallDir, "lib"), new File(packageInstallDir, "lib64")};
                            int length3 = libDirs2.length;
                            int i4 = 0;
                            while (i4 < length3) {
                                File libDir = libDirs2[i4];
                                if (!libDir.exists()) {
                                    libDirs = libDirs2;
                                    removedFiles = removedFiles3;
                                } else if (!libDir.isDirectory()) {
                                    libDirs = libDirs2;
                                    removedFiles = removedFiles3;
                                } else {
                                    List<File> libDirsToInherit3 = new LinkedList<>();
                                    File[] listFiles = libDir.listFiles();
                                    int length4 = listFiles.length;
                                    libDirs = libDirs2;
                                    int i5 = 0;
                                    while (i5 < length4) {
                                        int i6 = length4;
                                        File archSubDir2 = listFiles[i5];
                                        if (!archSubDir2.isDirectory()) {
                                            fileArr = listFiles;
                                            removedFiles2 = removedFiles3;
                                            libDirsToInherit2 = libDirsToInherit3;
                                        } else {
                                            try {
                                                String relLibPath = getRelativePath(archSubDir2, packageInstallDir);
                                                fileArr = listFiles;
                                                removedFiles2 = removedFiles3;
                                                if (!this.mResolvedNativeLibPaths.contains(relLibPath)) {
                                                    this.mResolvedNativeLibPaths.add(relLibPath);
                                                }
                                                libDirsToInherit2 = libDirsToInherit3;
                                                libDirsToInherit2.addAll(Arrays.asList(archSubDir2.listFiles()));
                                            } catch (IOException e3) {
                                                removedFiles = removedFiles3;
                                                libDirsToInherit = libDirsToInherit3;
                                                Slog.e(TAG, "Skipping linking of native library directory!", e3);
                                                libDirsToInherit.clear();
                                            }
                                        }
                                        i5++;
                                        libDirsToInherit3 = libDirsToInherit2;
                                        length4 = i6;
                                        listFiles = fileArr;
                                        removedFiles3 = removedFiles2;
                                    }
                                    removedFiles = removedFiles3;
                                    libDirsToInherit = libDirsToInherit3;
                                    this.mResolvedInheritedFiles.addAll(libDirsToInherit);
                                }
                                i4++;
                                libDirs2 = libDirs;
                                removedFiles3 = removedFiles;
                            }
                        }
                    } catch (PackageParser.PackageParserException e4) {
                        throw PackageManagerException.from(e4);
                    }
                }
                if (baseApk.useEmbeddedDex) {
                    for (File file : this.mResolvedStagedFiles) {
                        if (file.getName().endsWith(".apk") && !DexManager.auditUncompressedDexInApk(file.getPath())) {
                            throw new PackageManagerException(-2, "Some dex are not uncompressed and aligned correctly for " + this.mPackageName);
                        }
                    }
                }
                if (baseApk.isSplitRequired && stagedSplits.size() <= 1) {
                    throw new PackageManagerException(-28, "Missing split for " + this.mPackageName);
                }
                return;
            } catch (IOException e5) {
                throw new PackageManagerException(-18, "Failed to resolve stage location", e5);
            }
        }
        throw new PackageManagerException(-2, "Missing existing base package");
    }

    private void resolveAndStageFile(File origFile, File targetFile) throws PackageManagerException {
        this.mResolvedStagedFiles.add(targetFile);
        maybeRenameFile(origFile, targetFile);
        File originalSignature = new File(VerityUtils.getFsveritySignatureFilePath(origFile.getPath()));
        if (originalSignature.exists()) {
            if (!this.mVerityFound) {
                this.mVerityFound = true;
                if (this.mResolvedStagedFiles.size() > 1) {
                    throw new PackageManagerException(-118, "Some file is missing fs-verity signature");
                }
            }
            File stagedSignature = new File(VerityUtils.getFsveritySignatureFilePath(targetFile.getPath()));
            maybeRenameFile(originalSignature, stagedSignature);
            this.mResolvedStagedFiles.add(stagedSignature);
        } else if (!this.mVerityFound) {
        } else {
            throw new PackageManagerException(-118, "Missing corresponding fs-verity signature to " + origFile);
        }
    }

    private void resolveInheritedFile(File origFile) {
        this.mResolvedInheritedFiles.add(origFile);
        File fsveritySignatureFile = new File(VerityUtils.getFsveritySignatureFilePath(origFile.getPath()));
        if (fsveritySignatureFile.exists()) {
            this.mResolvedInheritedFiles.add(fsveritySignatureFile);
        }
    }

    @GuardedBy({"mLock"})
    private void assertApkConsistentLocked(String tag, PackageParser.ApkLite apk) throws PackageManagerException {
        if (!this.mPackageName.equals(apk.packageName)) {
            throw new PackageManagerException(-2, tag + " package " + apk.packageName + " inconsistent with " + this.mPackageName);
        } else if (this.params.appPackageName != null && !this.params.appPackageName.equals(apk.packageName)) {
            throw new PackageManagerException(-2, tag + " specified package " + this.params.appPackageName + " inconsistent with " + apk.packageName);
        } else if (this.mVersionCode != apk.getLongVersionCode()) {
            throw new PackageManagerException(-2, tag + " version code " + apk.versionCode + " inconsistent with " + this.mVersionCode);
        } else if (!this.mSigningDetails.signaturesMatchExactly(apk.signingDetails)) {
            throw new PackageManagerException(-2, tag + " signatures are inconsistent");
        }
    }

    private boolean isLinkPossible(List<File> fromFiles, File toDir) {
        try {
            StructStat toStat = Os.stat(toDir.getAbsolutePath());
            for (File fromFile : fromFiles) {
                StructStat fromStat = Os.stat(fromFile.getAbsolutePath());
                if (fromStat.st_dev != toStat.st_dev) {
                    return false;
                }
            }
            return true;
        } catch (ErrnoException e) {
            Slog.w(TAG, "Failed to detect if linking possible: " + e);
            return false;
        }
    }

    public int getInstallerUid() {
        int i;
        synchronized (this.mLock) {
            i = this.mInstallerUid;
        }
        return i;
    }

    public long getUpdatedMillis() {
        long j;
        synchronized (this.mLock) {
            j = this.updatedMillis;
        }
        return j;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getInstallerPackageName() {
        String str;
        synchronized (this.mLock) {
            str = this.mInstallerPackageName;
        }
        return str;
    }

    private static String getRelativePath(File file, File base) throws IOException {
        String pathStr = file.getAbsolutePath();
        String baseStr = base.getAbsolutePath();
        if (pathStr.contains("/.")) {
            throw new IOException("Invalid path (was relative) : " + pathStr);
        } else if (pathStr.startsWith(baseStr)) {
            return pathStr.substring(baseStr.length());
        } else {
            throw new IOException("File: " + pathStr + " outside base: " + baseStr);
        }
    }

    private void createOatDirs(List<String> instructionSets, File fromDir) throws PackageManagerException {
        for (String instructionSet : instructionSets) {
            try {
                this.mPm.mInstaller.createOatDir(fromDir.getAbsolutePath(), instructionSet);
            } catch (Installer.InstallerException e) {
                throw PackageManagerException.from(e);
            }
        }
    }

    private void linkFiles(List<File> fromFiles, File toDir, File fromDir) throws IOException {
        for (File fromFile : fromFiles) {
            String relativePath = getRelativePath(fromFile, fromDir);
            try {
                this.mPm.mInstaller.linkFile(relativePath, fromDir.getAbsolutePath(), toDir.getAbsolutePath());
            } catch (Installer.InstallerException e) {
                throw new IOException("failed linkOrCreateDir(" + relativePath + ", " + fromDir + ", " + toDir + ")", e);
            }
        }
        Slog.d(TAG, "Linked " + fromFiles.size() + " files into " + toDir);
    }

    private static void copyFiles(List<File> fromFiles, File toDir) throws IOException {
        File[] listFiles;
        for (File file : toDir.listFiles()) {
            if (file.getName().endsWith(".tmp")) {
                file.delete();
            }
        }
        for (File fromFile : fromFiles) {
            File tmpFile = File.createTempFile("inherit", ".tmp", toDir);
            Slog.d(TAG, "Copying " + fromFile + " to " + tmpFile);
            if (!FileUtils.copyFile(fromFile, tmpFile)) {
                throw new IOException("Failed to copy " + fromFile + " to " + tmpFile);
            }
            try {
                Os.chmod(tmpFile.getAbsolutePath(), 420);
                File toFile = new File(toDir, fromFile.getName());
                Slog.d(TAG, "Renaming " + tmpFile + " to " + toFile);
                if (!tmpFile.renameTo(toFile)) {
                    throw new IOException("Failed to rename " + tmpFile + " to " + toFile);
                }
            } catch (ErrnoException e) {
                throw new IOException("Failed to chmod " + tmpFile);
            }
        }
        Slog.d(TAG, "Copied " + fromFiles.size() + " files into " + toDir);
    }

    private static void extractNativeLibraries(File packageDir, String abiOverride, boolean inherit) throws PackageManagerException {
        File libDir = new File(packageDir, "lib");
        if (!inherit) {
            NativeLibraryHelper.removeNativeBinariesFromDirLI(libDir, true);
        }
        NativeLibraryHelper.Handle handle = null;
        try {
            try {
                handle = NativeLibraryHelper.Handle.create(packageDir);
                int res = NativeLibraryHelper.copyNativeBinariesWithOverride(handle, libDir, abiOverride);
                if (res != 1) {
                    throw new PackageManagerException(res, "Failed to extract native libraries, res=" + res);
                }
            } catch (IOException e) {
                throw new PackageManagerException(RequestStatus.SYS_ETIMEDOUT, "Failed to extract native libraries", e);
            }
        } finally {
            IoUtils.closeQuietly(handle);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPermissionsResult(boolean accepted) {
        if (!this.mSealed) {
            throw new SecurityException("Must be sealed to accept permissions");
        }
        if (accepted) {
            synchronized (this.mLock) {
                this.mPermissionsManuallyAccepted = true;
                this.mHandler.obtainMessage(1).sendToTarget();
            }
            return;
        }
        destroyInternal();
        dispatchSessionFinished(-115, "User rejected permissions", null);
    }

    void addChildSessionIdInternal(int sessionId) {
        this.mChildSessionIds.put(sessionId, 0);
    }

    public void open() throws IOException {
        boolean wasPrepared;
        if (this.mActiveCount.getAndIncrement() == 0) {
            this.mCallback.onSessionActiveChanged(this, true);
        }
        synchronized (this.mLock) {
            wasPrepared = this.mPrepared;
            if (!this.mPrepared) {
                if (this.stageDir != null) {
                    PackageInstallerService.prepareStageDir(this.stageDir);
                } else if (!this.params.isMultiPackage) {
                    throw new IllegalArgumentException("stageDir must be set");
                }
                this.mPrepared = true;
            }
        }
        if (!wasPrepared) {
            this.mCallback.onSessionPrepared(this);
        }
    }

    public void close() {
        closeInternal(true);
    }

    private void closeInternal(boolean checkCaller) {
        int activeCount;
        synchronized (this.mLock) {
            if (checkCaller) {
                assertCallerIsOwnerOrRootLocked();
            }
            activeCount = this.mActiveCount.decrementAndGet();
        }
        if (activeCount == 0) {
            this.mCallback.onSessionActiveChanged(this, false);
        }
    }

    public void abandon() {
        if (hasParentSessionId()) {
            throw new IllegalStateException("Session " + this.sessionId + " is a child of multi-package session " + this.mParentSessionId + " and may not be abandoned directly.");
        }
        synchronized (this.mLock) {
            assertCallerIsOwnerOrRootLocked();
            if (isStagedAndInTerminalState()) {
                return;
            }
            if (this.mCommitted && this.params.isStaged) {
                synchronized (this.mLock) {
                    this.mDestroyed = true;
                }
                this.mStagingManager.abortCommittedSession(this);
                cleanStageDir();
            }
            if (this.mRelinquished) {
                Slog.d(TAG, "Ignoring abandon after commit relinquished control");
                return;
            }
            destroyInternal();
            dispatchSessionFinished(-115, "Session was abandoned", null);
        }
    }

    public boolean isMultiPackage() {
        return this.params.isMultiPackage;
    }

    public boolean isStaged() {
        return this.params.isStaged;
    }

    public int[] getChildSessionIds() {
        int[] childSessionIds = this.mChildSessionIds.copyKeys();
        if (childSessionIds != null) {
            return childSessionIds;
        }
        return EMPTY_CHILD_SESSION_ARRAY;
    }

    public void addChildSessionId(int childSessionId) {
        PackageInstallerSession childSession = this.mSessionProvider.getSession(childSessionId);
        if (childSession == null || ((childSession.hasParentSessionId() && childSession.mParentSessionId != this.sessionId) || childSession.mCommitted || childSession.mDestroyed)) {
            throw new IllegalStateException("Unable to add child session " + childSessionId + " as it does not exist or is in an invalid state.");
        }
        synchronized (this.mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotSealedLocked("addChildSessionId");
            int indexOfSession = this.mChildSessionIds.indexOfKey(childSessionId);
            if (indexOfSession >= 0) {
                return;
            }
            childSession.setParentSessionId(this.sessionId);
            addChildSessionIdInternal(childSessionId);
        }
    }

    public void removeChildSessionId(int sessionId) {
        PackageInstallerSession session = this.mSessionProvider.getSession(sessionId);
        synchronized (this.mLock) {
            int indexOfSession = this.mChildSessionIds.indexOfKey(sessionId);
            if (session != null) {
                session.setParentSessionId(-1);
            }
            if (indexOfSession < 0) {
                return;
            }
            this.mChildSessionIds.removeAt(indexOfSession);
        }
    }

    void setParentSessionId(int parentSessionId) {
        synchronized (this.mLock) {
            if (parentSessionId != -1) {
                if (this.mParentSessionId != -1) {
                    throw new IllegalStateException("The parent of " + this.sessionId + " is alreadyset to " + this.mParentSessionId);
                }
            }
            this.mParentSessionId = parentSessionId;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasParentSessionId() {
        return this.mParentSessionId != -1;
    }

    public int getParentSessionId() {
        return this.mParentSessionId;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchSessionFinished(int returnCode, String msg, Bundle extras) {
        IPackageInstallObserver2 observer;
        String packageName;
        synchronized (this.mLock) {
            this.mFinalStatus = returnCode;
            this.mFinalMessage = msg;
            observer = this.mRemoteObserver;
            packageName = this.mPackageName;
        }
        if (observer != null) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = packageName;
            args.arg2 = msg;
            args.arg3 = extras;
            args.arg4 = observer;
            args.argi1 = returnCode;
            this.mHandler.obtainMessage(2, args).sendToTarget();
        }
        boolean isNewInstall = false;
        boolean success = returnCode == 1;
        if (extras == null || !extras.getBoolean("android.intent.extra.REPLACING")) {
            isNewInstall = true;
        }
        if (success && isNewInstall && this.mPm.mInstallerService.okToSendBroadcasts()) {
            this.mPm.sendSessionCommitBroadcast(generateInfoScrubbed(true), this.userId);
        }
        this.mCallback.onSessionFinished(this, success);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setStagedSessionReady() {
        synchronized (this.mLock) {
            this.mStagedSessionReady = true;
            this.mStagedSessionApplied = false;
            this.mStagedSessionFailed = false;
            this.mStagedSessionErrorCode = 0;
            this.mStagedSessionErrorMessage = "";
        }
        this.mCallback.onStagedSessionChanged(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setStagedSessionFailed(int errorCode, String errorMessage) {
        synchronized (this.mLock) {
            this.mStagedSessionReady = false;
            this.mStagedSessionApplied = false;
            this.mStagedSessionFailed = true;
            this.mStagedSessionErrorCode = errorCode;
            this.mStagedSessionErrorMessage = errorMessage;
            Slog.d(TAG, "Marking session " + this.sessionId + " as failed: " + errorMessage);
        }
        cleanStageDir();
        this.mCallback.onStagedSessionChanged(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setStagedSessionApplied() {
        synchronized (this.mLock) {
            this.mStagedSessionReady = false;
            this.mStagedSessionApplied = true;
            this.mStagedSessionFailed = false;
            this.mStagedSessionErrorCode = 0;
            this.mStagedSessionErrorMessage = "";
            Slog.d(TAG, "Marking session " + this.sessionId + " as applied");
        }
        cleanStageDir();
        this.mCallback.onStagedSessionChanged(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isStagedSessionReady() {
        return this.mStagedSessionReady;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isStagedSessionApplied() {
        return this.mStagedSessionApplied;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isStagedSessionFailed() {
        return this.mStagedSessionFailed;
    }

    int getStagedSessionErrorCode() {
        return this.mStagedSessionErrorCode;
    }

    String getStagedSessionErrorMessage() {
        return this.mStagedSessionErrorMessage;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void destroyInternal() {
        synchronized (this.mLock) {
            this.mSealed = true;
            if (!this.params.isStaged || isStagedAndInTerminalState()) {
                this.mDestroyed = true;
            }
            Iterator<RevocableFileDescriptor> it = this.mFds.iterator();
            while (it.hasNext()) {
                RevocableFileDescriptor fd = it.next();
                fd.revoke();
            }
            Iterator<FileBridge> it2 = this.mBridges.iterator();
            while (it2.hasNext()) {
                FileBridge bridge = it2.next();
                bridge.forceClose();
            }
        }
        if (this.stageDir != null && !this.params.isStaged) {
            try {
                this.mPm.mInstaller.rmPackageDir(this.stageDir.getAbsolutePath());
            } catch (Installer.InstallerException e) {
            }
        }
    }

    private void cleanStageDir() {
        int[] childSessionIds;
        if (isMultiPackage()) {
            for (int childSessionId : getChildSessionIds()) {
                this.mSessionProvider.getSession(childSessionId).cleanStageDir();
            }
            return;
        }
        try {
            this.mPm.mInstaller.rmPackageDir(this.stageDir.getAbsolutePath());
        } catch (Installer.InstallerException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(IndentingPrintWriter pw) {
        synchronized (this.mLock) {
            dumpLocked(pw);
        }
    }

    @GuardedBy({"mLock"})
    private void dumpLocked(IndentingPrintWriter pw) {
        pw.println("Session " + this.sessionId + ":");
        pw.increaseIndent();
        pw.printPair(ATTR_USER_ID, Integer.valueOf(this.userId));
        pw.printPair("mOriginalInstallerUid", Integer.valueOf(this.mOriginalInstallerUid));
        pw.printPair("mInstallerPackageName", this.mInstallerPackageName);
        pw.printPair("mInstallerUid", Integer.valueOf(this.mInstallerUid));
        pw.printPair(ATTR_CREATED_MILLIS, Long.valueOf(this.createdMillis));
        pw.printPair("stageDir", this.stageDir);
        pw.printPair("stageCid", this.stageCid);
        pw.println();
        this.params.dump(pw);
        pw.printPair("mClientProgress", Float.valueOf(this.mClientProgress));
        pw.printPair("mProgress", Float.valueOf(this.mProgress));
        pw.printPair("mCommitted", Boolean.valueOf(this.mCommitted));
        pw.printPair("mSealed", Boolean.valueOf(this.mSealed));
        pw.printPair("mPermissionsManuallyAccepted", Boolean.valueOf(this.mPermissionsManuallyAccepted));
        pw.printPair("mRelinquished", Boolean.valueOf(this.mRelinquished));
        pw.printPair("mDestroyed", Boolean.valueOf(this.mDestroyed));
        pw.printPair("mFds", Integer.valueOf(this.mFds.size()));
        pw.printPair("mBridges", Integer.valueOf(this.mBridges.size()));
        pw.printPair("mFinalStatus", Integer.valueOf(this.mFinalStatus));
        pw.printPair("mFinalMessage", this.mFinalMessage);
        pw.printPair("params.isMultiPackage", Boolean.valueOf(this.params.isMultiPackage));
        pw.printPair("params.isStaged", Boolean.valueOf(this.params.isStaged));
        pw.println();
        pw.decreaseIndent();
    }

    private static void writeGrantedRuntimePermissionsLocked(XmlSerializer out, String[] grantedRuntimePermissions) throws IOException {
        if (grantedRuntimePermissions != null) {
            for (String permission : grantedRuntimePermissions) {
                out.startTag(null, TAG_GRANTED_RUNTIME_PERMISSION);
                XmlUtils.writeStringAttribute(out, "name", permission);
                out.endTag(null, TAG_GRANTED_RUNTIME_PERMISSION);
            }
        }
    }

    private static void writeWhitelistedRestrictedPermissionsLocked(XmlSerializer out, List<String> whitelistedRestrictedPermissions) throws IOException {
        if (whitelistedRestrictedPermissions != null) {
            int permissionCount = whitelistedRestrictedPermissions.size();
            for (int i = 0; i < permissionCount; i++) {
                out.startTag(null, TAG_WHITELISTED_RESTRICTED_PERMISSION);
                XmlUtils.writeStringAttribute(out, "name", whitelistedRestrictedPermissions.get(i));
                out.endTag(null, TAG_WHITELISTED_RESTRICTED_PERMISSION);
            }
        }
    }

    private static File buildAppIconFile(int sessionId, File sessionsDir) {
        return new File(sessionsDir, "app_icon." + sessionId + ".png");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void write(XmlSerializer out, File sessionsDir) throws IOException {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                return;
            }
            out.startTag(null, TAG_SESSION);
            XmlUtils.writeIntAttribute(out, ATTR_SESSION_ID, this.sessionId);
            XmlUtils.writeIntAttribute(out, ATTR_USER_ID, this.userId);
            XmlUtils.writeStringAttribute(out, ATTR_INSTALLER_PACKAGE_NAME, this.mInstallerPackageName);
            XmlUtils.writeIntAttribute(out, ATTR_INSTALLER_UID, this.mInstallerUid);
            XmlUtils.writeLongAttribute(out, ATTR_CREATED_MILLIS, this.createdMillis);
            XmlUtils.writeLongAttribute(out, ATTR_UPDATED_MILLIS, this.updatedMillis);
            if (this.stageDir != null) {
                XmlUtils.writeStringAttribute(out, ATTR_SESSION_STAGE_DIR, this.stageDir.getAbsolutePath());
            }
            if (this.stageCid != null) {
                XmlUtils.writeStringAttribute(out, ATTR_SESSION_STAGE_CID, this.stageCid);
            }
            XmlUtils.writeBooleanAttribute(out, ATTR_PREPARED, isPrepared());
            XmlUtils.writeBooleanAttribute(out, ATTR_COMMITTED, isCommitted());
            XmlUtils.writeBooleanAttribute(out, ATTR_SEALED, isSealed());
            XmlUtils.writeBooleanAttribute(out, ATTR_MULTI_PACKAGE, this.params.isMultiPackage);
            XmlUtils.writeBooleanAttribute(out, ATTR_STAGED_SESSION, this.params.isStaged);
            XmlUtils.writeBooleanAttribute(out, ATTR_IS_READY, this.mStagedSessionReady);
            XmlUtils.writeBooleanAttribute(out, ATTR_IS_FAILED, this.mStagedSessionFailed);
            XmlUtils.writeBooleanAttribute(out, ATTR_IS_APPLIED, this.mStagedSessionApplied);
            XmlUtils.writeIntAttribute(out, ATTR_STAGED_SESSION_ERROR_CODE, this.mStagedSessionErrorCode);
            XmlUtils.writeStringAttribute(out, ATTR_STAGED_SESSION_ERROR_MESSAGE, this.mStagedSessionErrorMessage);
            XmlUtils.writeIntAttribute(out, ATTR_PARENT_SESSION_ID, this.mParentSessionId);
            XmlUtils.writeIntAttribute(out, "mode", this.params.mode);
            XmlUtils.writeIntAttribute(out, ATTR_INSTALL_FLAGS, this.params.installFlags);
            XmlUtils.writeIntAttribute(out, ATTR_INSTALL_LOCATION, this.params.installLocation);
            XmlUtils.writeLongAttribute(out, ATTR_SIZE_BYTES, this.params.sizeBytes);
            XmlUtils.writeStringAttribute(out, ATTR_APP_PACKAGE_NAME, this.params.appPackageName);
            XmlUtils.writeStringAttribute(out, ATTR_APP_LABEL, this.params.appLabel);
            XmlUtils.writeUriAttribute(out, ATTR_ORIGINATING_URI, this.params.originatingUri);
            XmlUtils.writeIntAttribute(out, ATTR_ORIGINATING_UID, this.params.originatingUid);
            XmlUtils.writeUriAttribute(out, ATTR_REFERRER_URI, this.params.referrerUri);
            XmlUtils.writeStringAttribute(out, ATTR_ABI_OVERRIDE, this.params.abiOverride);
            XmlUtils.writeStringAttribute(out, ATTR_VOLUME_UUID, this.params.volumeUuid);
            XmlUtils.writeIntAttribute(out, ATTR_INSTALL_REASON, this.params.installReason);
            writeGrantedRuntimePermissionsLocked(out, this.params.grantedRuntimePermissions);
            writeWhitelistedRestrictedPermissionsLocked(out, this.params.whitelistedRestrictedPermissions);
            File appIconFile = buildAppIconFile(this.sessionId, sessionsDir);
            if (this.params.appIcon == null && appIconFile.exists()) {
                appIconFile.delete();
            } else if (this.params.appIcon != null && appIconFile.lastModified() != this.params.appIconLastModified) {
                Slog.w(TAG, "Writing changed icon " + appIconFile);
                FileOutputStream os = null;
                try {
                    os = new FileOutputStream(appIconFile);
                    this.params.appIcon.compress(Bitmap.CompressFormat.PNG, 90, os);
                    IoUtils.closeQuietly(os);
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to write icon " + appIconFile + ": " + e.getMessage());
                    IoUtils.closeQuietly(os);
                }
                this.params.appIconLastModified = appIconFile.lastModified();
            }
            int[] childSessionIds = getChildSessionIds();
            for (int childSessionId : childSessionIds) {
                out.startTag(null, TAG_CHILD_SESSION);
                XmlUtils.writeIntAttribute(out, ATTR_SESSION_ID, childSessionId);
                out.endTag(null, TAG_CHILD_SESSION);
            }
            out.endTag(null, TAG_SESSION);
        }
    }

    private static boolean isStagedSessionStateValid(boolean isReady, boolean isApplied, boolean isFailed) {
        return ((isReady || isApplied || isFailed) && (!isReady || isApplied || isFailed) && ((isReady || !isApplied || isFailed) && (isReady || isApplied || !isFailed))) ? false : true;
    }

    public static PackageInstallerSession readFromXml(XmlPullParser in, PackageInstallerService.InternalCallback callback, Context context, PackageManagerService pm, Looper installerThread, StagingManager stagingManager, File sessionsDir, PackageSessionProvider sessionProvider) throws IOException, XmlPullParserException {
        boolean isApplied;
        int outerDepth;
        ArrayList arrayList;
        int type;
        int[] childSessionIdsArray;
        int sessionId = XmlUtils.readIntAttribute(in, ATTR_SESSION_ID);
        int userId = XmlUtils.readIntAttribute(in, ATTR_USER_ID);
        String installerPackageName = XmlUtils.readStringAttribute(in, ATTR_INSTALLER_PACKAGE_NAME);
        int installerUid = XmlUtils.readIntAttribute(in, ATTR_INSTALLER_UID, pm.getPackageUid(installerPackageName, 8192, userId));
        long createdMillis = XmlUtils.readLongAttribute(in, ATTR_CREATED_MILLIS);
        XmlUtils.readLongAttribute(in, ATTR_UPDATED_MILLIS);
        String stageDirRaw = XmlUtils.readStringAttribute(in, ATTR_SESSION_STAGE_DIR);
        File stageDir = stageDirRaw != null ? new File(stageDirRaw) : null;
        String stageCid = XmlUtils.readStringAttribute(in, ATTR_SESSION_STAGE_CID);
        int i = 1;
        boolean prepared = XmlUtils.readBooleanAttribute(in, ATTR_PREPARED, true);
        boolean committed = XmlUtils.readBooleanAttribute(in, ATTR_COMMITTED);
        boolean sealed = XmlUtils.readBooleanAttribute(in, ATTR_SEALED);
        int parentSessionId = XmlUtils.readIntAttribute(in, ATTR_PARENT_SESSION_ID, -1);
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(-1);
        params.isMultiPackage = XmlUtils.readBooleanAttribute(in, ATTR_MULTI_PACKAGE, false);
        params.isStaged = XmlUtils.readBooleanAttribute(in, ATTR_STAGED_SESSION, false);
        params.mode = XmlUtils.readIntAttribute(in, "mode");
        params.installFlags = XmlUtils.readIntAttribute(in, ATTR_INSTALL_FLAGS);
        params.installLocation = XmlUtils.readIntAttribute(in, ATTR_INSTALL_LOCATION);
        params.sizeBytes = XmlUtils.readLongAttribute(in, ATTR_SIZE_BYTES);
        params.appPackageName = XmlUtils.readStringAttribute(in, ATTR_APP_PACKAGE_NAME);
        params.appIcon = XmlUtils.readBitmapAttribute(in, ATTR_APP_ICON);
        params.appLabel = XmlUtils.readStringAttribute(in, ATTR_APP_LABEL);
        params.originatingUri = XmlUtils.readUriAttribute(in, ATTR_ORIGINATING_URI);
        params.originatingUid = XmlUtils.readIntAttribute(in, ATTR_ORIGINATING_UID, -1);
        params.referrerUri = XmlUtils.readUriAttribute(in, ATTR_REFERRER_URI);
        params.abiOverride = XmlUtils.readStringAttribute(in, ATTR_ABI_OVERRIDE);
        params.volumeUuid = XmlUtils.readStringAttribute(in, ATTR_VOLUME_UUID);
        params.installReason = XmlUtils.readIntAttribute(in, ATTR_INSTALL_REASON);
        File appIconFile = buildAppIconFile(sessionId, sessionsDir);
        if (appIconFile.exists()) {
            params.appIcon = BitmapFactory.decodeFile(appIconFile.getAbsolutePath());
            params.appIconLastModified = appIconFile.lastModified();
        }
        boolean isReady = XmlUtils.readBooleanAttribute(in, ATTR_IS_READY);
        boolean isFailed = XmlUtils.readBooleanAttribute(in, ATTR_IS_FAILED);
        boolean isApplied2 = XmlUtils.readBooleanAttribute(in, ATTR_IS_APPLIED);
        int stagedSessionErrorCode = XmlUtils.readIntAttribute(in, ATTR_STAGED_SESSION_ERROR_CODE, 0);
        String stagedSessionErrorMessage = XmlUtils.readStringAttribute(in, ATTR_STAGED_SESSION_ERROR_MESSAGE);
        if (!isStagedSessionStateValid(isReady, isApplied2, isFailed)) {
            throw new IllegalArgumentException("Can't restore staged session with invalid state.");
        }
        List<String> grantedRuntimePermissions = new ArrayList<>();
        List<String> whitelistedRestrictedPermissions = new ArrayList<>();
        ArrayList arrayList2 = new ArrayList();
        int outerDepth2 = in.getDepth();
        while (true) {
            isApplied = isApplied2;
            int type2 = in.next();
            if (type2 == i) {
                outerDepth = outerDepth2;
                arrayList = arrayList2;
                type = type2;
                break;
            }
            type = type2;
            if (type == 3 && in.getDepth() <= outerDepth2) {
                outerDepth = outerDepth2;
                arrayList = arrayList2;
                break;
            } else if (type == 3) {
                isApplied2 = isApplied;
                i = 1;
            } else if (type == 4) {
                isApplied2 = isApplied;
                i = 1;
            } else {
                if (TAG_GRANTED_RUNTIME_PERMISSION.equals(in.getName())) {
                    grantedRuntimePermissions.add(XmlUtils.readStringAttribute(in, "name"));
                }
                int outerDepth3 = outerDepth2;
                if (TAG_WHITELISTED_RESTRICTED_PERMISSION.equals(in.getName())) {
                    whitelistedRestrictedPermissions.add(XmlUtils.readStringAttribute(in, "name"));
                }
                if (TAG_CHILD_SESSION.equals(in.getName())) {
                    arrayList2.add(Integer.valueOf(XmlUtils.readIntAttribute(in, ATTR_SESSION_ID, -1)));
                    isApplied2 = isApplied;
                    outerDepth2 = outerDepth3;
                    i = 1;
                } else {
                    isApplied2 = isApplied;
                    outerDepth2 = outerDepth3;
                    i = 1;
                }
            }
        }
        if (grantedRuntimePermissions.size() > 0) {
            params.grantedRuntimePermissions = (String[]) grantedRuntimePermissions.stream().toArray(new IntFunction() { // from class: com.android.server.pm.-$$Lambda$PackageInstallerSession$7Sec-athzbWSLkwYbdNW1Dgq0jU
                @Override // java.util.function.IntFunction
                public final Object apply(int i2) {
                    return PackageInstallerSession.lambda$readFromXml$1(i2);
                }
            });
        }
        if (whitelistedRestrictedPermissions.size() > 0) {
            params.whitelistedRestrictedPermissions = whitelistedRestrictedPermissions;
        }
        if (arrayList.size() > 0) {
            childSessionIdsArray = arrayList.stream().mapToInt(new ToIntFunction() { // from class: com.android.server.pm.-$$Lambda$PackageInstallerSession$fMSKA3sU8i-wLB8uwZHGaX-jhFI
                @Override // java.util.function.ToIntFunction
                public final int applyAsInt(Object obj) {
                    int intValue;
                    intValue = ((Integer) obj).intValue();
                    return intValue;
                }
            }).toArray();
        } else {
            childSessionIdsArray = EMPTY_CHILD_SESSION_ARRAY;
        }
        return new PackageInstallerSession(callback, context, pm, sessionProvider, installerThread, stagingManager, sessionId, userId, installerPackageName, installerUid, params, createdMillis, stageDir, stageCid, prepared, committed, sealed, childSessionIdsArray, parentSessionId, isReady, isFailed, isApplied, stagedSessionErrorCode, stagedSessionErrorMessage);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ String[] lambda$readFromXml$1(int x$0) {
        return new String[x$0];
    }

    static int readChildSessionIdFromXml(XmlPullParser in) {
        return XmlUtils.readIntAttribute(in, ATTR_SESSION_ID, -1);
    }
}
