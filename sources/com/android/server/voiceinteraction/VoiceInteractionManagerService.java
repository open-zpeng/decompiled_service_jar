package com.android.server.voiceinteraction;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.voice.IVoiceInteractionService;
import android.service.voice.IVoiceInteractionSession;
import android.service.voice.VoiceInteractionManagerInternal;
import android.service.voice.VoiceInteractionServiceInfo;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.TimingsTraceLog;
import com.android.internal.app.IVoiceActionCheckCallback;
import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.app.IVoiceInteractionSessionListener;
import com.android.internal.app.IVoiceInteractionSessionShowCallback;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.UiThread;
import com.android.server.soundtrigger.SoundTriggerInternal;
import com.android.server.voiceinteraction.VoiceInteractionManagerService;
import com.android.server.wm.ActivityTaskManagerInternal;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/* loaded from: classes2.dex */
public class VoiceInteractionManagerService extends SystemService {
    static final boolean DEBUG = false;
    static final String TAG = "VoiceInteractionManagerService";
    final ActivityManagerInternal mAmInternal;
    final ActivityTaskManagerInternal mAtmInternal;
    final Context mContext;
    final DatabaseHelper mDbHelper;
    final ArraySet<Integer> mLoadedKeyphraseIds;
    final ContentResolver mResolver;
    private final VoiceInteractionManagerServiceStub mServiceStub;
    ShortcutServiceInternal mShortcutServiceInternal;
    SoundTriggerInternal mSoundTriggerInternal;
    final UserManager mUserManager;
    private final RemoteCallbackList<IVoiceInteractionSessionListener> mVoiceInteractionSessionListeners;

    public VoiceInteractionManagerService(Context context) {
        super(context);
        this.mLoadedKeyphraseIds = new ArraySet<>();
        this.mVoiceInteractionSessionListeners = new RemoteCallbackList<>();
        this.mContext = context;
        this.mResolver = context.getContentResolver();
        this.mDbHelper = new DatabaseHelper(context);
        this.mServiceStub = new VoiceInteractionManagerServiceStub();
        this.mAmInternal = (ActivityManagerInternal) Preconditions.checkNotNull((ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class));
        this.mAtmInternal = (ActivityTaskManagerInternal) Preconditions.checkNotNull((ActivityTaskManagerInternal) LocalServices.getService(ActivityTaskManagerInternal.class));
        this.mUserManager = (UserManager) Preconditions.checkNotNull((UserManager) context.getSystemService(UserManager.class));
        PackageManagerInternal packageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        packageManagerInternal.setVoiceInteractionPackagesProvider(new PackageManagerInternal.PackagesProvider() { // from class: com.android.server.voiceinteraction.VoiceInteractionManagerService.1
            public String[] getPackages(int userId) {
                VoiceInteractionManagerService.this.mServiceStub.initForUser(userId);
                ComponentName interactor = VoiceInteractionManagerService.this.mServiceStub.getCurInteractor(userId);
                if (interactor != null) {
                    return new String[]{interactor.getPackageName()};
                }
                return null;
            }
        });
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishBinderService("voiceinteraction", this.mServiceStub);
        publishLocalService(VoiceInteractionManagerInternal.class, new LocalService());
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (500 == phase) {
            this.mShortcutServiceInternal = (ShortcutServiceInternal) Preconditions.checkNotNull((ShortcutServiceInternal) LocalServices.getService(ShortcutServiceInternal.class));
            this.mSoundTriggerInternal = (SoundTriggerInternal) LocalServices.getService(SoundTriggerInternal.class);
        } else if (phase == 600) {
            this.mServiceStub.systemRunning(isSafeMode());
        }
    }

    @Override // com.android.server.SystemService
    public void onStartUser(int userHandle) {
        this.mServiceStub.initForUser(userHandle);
    }

    @Override // com.android.server.SystemService
    public void onUnlockUser(int userHandle) {
        this.mServiceStub.initForUser(userHandle);
        this.mServiceStub.switchImplementationIfNeeded(false);
    }

    @Override // com.android.server.SystemService
    public void onSwitchUser(int userHandle) {
        this.mServiceStub.switchUser(userHandle);
    }

    /* loaded from: classes2.dex */
    class LocalService extends VoiceInteractionManagerInternal {
        LocalService() {
        }

        public void startLocalVoiceInteraction(IBinder callingActivity, Bundle options) {
            VoiceInteractionManagerService.this.mServiceStub.startLocalVoiceInteraction(callingActivity, options);
        }

        public boolean supportsLocalVoiceInteraction() {
            return VoiceInteractionManagerService.this.mServiceStub.supportsLocalVoiceInteraction();
        }

        public void stopLocalVoiceInteraction(IBinder callingActivity) {
            VoiceInteractionManagerService.this.mServiceStub.stopLocalVoiceInteraction(callingActivity);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public class VoiceInteractionManagerServiceStub extends IVoiceInteractionManagerService.Stub {
        private int mCurUser;
        private boolean mCurUserUnlocked;
        private final boolean mEnableService;
        VoiceInteractionManagerServiceImpl mImpl;
        PackageMonitor mPackageMonitor = new AnonymousClass2();
        private boolean mSafeMode;

        VoiceInteractionManagerServiceStub() {
            this.mEnableService = shouldEnableService(VoiceInteractionManagerService.this.mContext);
            new RoleObserver(VoiceInteractionManagerService.this.mContext.getMainExecutor());
        }

        void startLocalVoiceInteraction(final IBinder token, Bundle options) {
            if (this.mImpl == null) {
                return;
            }
            long caller = Binder.clearCallingIdentity();
            try {
                this.mImpl.showSessionLocked(options, 16, new IVoiceInteractionSessionShowCallback.Stub() { // from class: com.android.server.voiceinteraction.VoiceInteractionManagerService.VoiceInteractionManagerServiceStub.1
                    public void onFailed() {
                    }

                    public void onShown() {
                        VoiceInteractionManagerService.this.mAtmInternal.onLocalVoiceInteractionStarted(token, VoiceInteractionManagerServiceStub.this.mImpl.mActiveSession.mSession, VoiceInteractionManagerServiceStub.this.mImpl.mActiveSession.mInteractor);
                    }
                }, token);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        public void stopLocalVoiceInteraction(IBinder callingActivity) {
            if (this.mImpl == null) {
                return;
            }
            long caller = Binder.clearCallingIdentity();
            try {
                this.mImpl.finishLocked(callingActivity, true);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        public boolean supportsLocalVoiceInteraction() {
            VoiceInteractionManagerServiceImpl voiceInteractionManagerServiceImpl = this.mImpl;
            if (voiceInteractionManagerServiceImpl == null) {
                return false;
            }
            return voiceInteractionManagerServiceImpl.supportsLocalVoiceInteraction();
        }

        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (RuntimeException e) {
                if (!(e instanceof SecurityException)) {
                    Slog.wtf(VoiceInteractionManagerService.TAG, "VoiceInteractionManagerService Crash", e);
                }
                throw e;
            }
        }

        public void initForUser(int userHandle) {
            TimingsTraceLog t = new TimingsTraceLog(VoiceInteractionManagerService.TAG, 524288L);
            t.traceBegin("initForUser(" + userHandle + ")");
            try {
                initForUserNoTracing(userHandle);
            } finally {
                t.traceEnd();
            }
        }

        private void initForUserNoTracing(int userHandle) {
            String curInteractorStr = Settings.Secure.getStringForUser(VoiceInteractionManagerService.this.mContext.getContentResolver(), "voice_interaction_service", userHandle);
            ComponentName curRecognizer = getCurRecognizer(userHandle);
            VoiceInteractionServiceInfo curInteractorInfo = null;
            if (curInteractorStr == null && curRecognizer != null && this.mEnableService && (curInteractorInfo = findAvailInteractor(userHandle, curRecognizer.getPackageName())) != null) {
                curRecognizer = null;
            }
            String forceInteractorPackage = getForceVoiceInteractionServicePackage(VoiceInteractionManagerService.this.mContext.getResources());
            if (forceInteractorPackage != null && (curInteractorInfo = findAvailInteractor(userHandle, forceInteractorPackage)) != null) {
                curRecognizer = null;
            }
            if (!this.mEnableService && curInteractorStr != null && !TextUtils.isEmpty(curInteractorStr)) {
                setCurInteractor(null, userHandle);
                curInteractorStr = "";
            }
            if (curRecognizer != null) {
                IPackageManager pm = AppGlobals.getPackageManager();
                ServiceInfo interactorInfo = null;
                ServiceInfo recognizerInfo = null;
                ComponentName curInteractor = !TextUtils.isEmpty(curInteractorStr) ? ComponentName.unflattenFromString(curInteractorStr) : null;
                try {
                    recognizerInfo = pm.getServiceInfo(curRecognizer, 786432, userHandle);
                    if (curInteractor != null) {
                        interactorInfo = pm.getServiceInfo(curInteractor, 786432, userHandle);
                    }
                } catch (RemoteException e) {
                }
                if (recognizerInfo != null && (curInteractor == null || interactorInfo != null)) {
                    return;
                }
            }
            if (curInteractorInfo == null && this.mEnableService) {
                curInteractorInfo = findAvailInteractor(userHandle, null);
            }
            if (curInteractorInfo != null) {
                setCurInteractor(new ComponentName(curInteractorInfo.getServiceInfo().packageName, curInteractorInfo.getServiceInfo().name), userHandle);
                if (curInteractorInfo.getRecognitionService() != null) {
                    setCurRecognizer(new ComponentName(curInteractorInfo.getServiceInfo().packageName, curInteractorInfo.getRecognitionService()), userHandle);
                    return;
                }
            }
            initSimpleRecognizer(curInteractorInfo, userHandle);
        }

        public void initSimpleRecognizer(VoiceInteractionServiceInfo curInteractorInfo, int userHandle) {
            ComponentName curRecognizer = findAvailRecognizer(null, userHandle);
            if (curRecognizer != null) {
                if (curInteractorInfo == null) {
                    setCurInteractor(null, userHandle);
                }
                setCurRecognizer(curRecognizer, userHandle);
            }
        }

        private boolean shouldEnableService(Context context) {
            if (getForceVoiceInteractionServicePackage(context.getResources()) != null) {
                return true;
            }
            return context.getPackageManager().hasSystemFeature("android.software.voice_recognizers");
        }

        private String getForceVoiceInteractionServicePackage(Resources res) {
            String interactorPackage = res.getString(17039737);
            if (TextUtils.isEmpty(interactorPackage)) {
                return null;
            }
            return interactorPackage;
        }

        public void systemRunning(boolean safeMode) {
            this.mSafeMode = safeMode;
            this.mPackageMonitor.register(VoiceInteractionManagerService.this.mContext, BackgroundThread.getHandler().getLooper(), UserHandle.ALL, true);
            new SettingsObserver(UiThread.getHandler());
            synchronized (this) {
                this.mCurUser = ActivityManager.getCurrentUser();
                switchImplementationIfNeededLocked(false);
            }
        }

        public void switchUser(final int userHandle) {
            FgThread.getHandler().post(new Runnable() { // from class: com.android.server.voiceinteraction.-$$Lambda$VoiceInteractionManagerService$VoiceInteractionManagerServiceStub$u4484DFAd6TvNnx89ISVr_ZLWJY
                @Override // java.lang.Runnable
                public final void run() {
                    VoiceInteractionManagerService.VoiceInteractionManagerServiceStub.this.lambda$switchUser$0$VoiceInteractionManagerService$VoiceInteractionManagerServiceStub(userHandle);
                }
            });
        }

        public /* synthetic */ void lambda$switchUser$0$VoiceInteractionManagerService$VoiceInteractionManagerServiceStub(int userHandle) {
            synchronized (this) {
                this.mCurUser = userHandle;
                this.mCurUserUnlocked = false;
                switchImplementationIfNeededLocked(false);
            }
        }

        void switchImplementationIfNeeded(boolean force) {
            synchronized (this) {
                switchImplementationIfNeededLocked(force);
            }
        }

        void switchImplementationIfNeededLocked(boolean force) {
            TimingsTraceLog t = new TimingsTraceLog(VoiceInteractionManagerService.TAG, 524288L);
            t.traceBegin("switchImplementation(" + this.mCurUser + ")");
            try {
                switchImplementationIfNeededNoTracingLocked(force);
            } finally {
                t.traceEnd();
            }
        }

        void switchImplementationIfNeededNoTracingLocked(boolean force) {
            VoiceInteractionManagerServiceImpl voiceInteractionManagerServiceImpl;
            if (!this.mSafeMode) {
                String curService = Settings.Secure.getStringForUser(VoiceInteractionManagerService.this.mResolver, "voice_interaction_service", this.mCurUser);
                ComponentName serviceComponent = null;
                ServiceInfo serviceInfo = null;
                boolean hasComponent = false;
                if (curService != null && !curService.isEmpty()) {
                    try {
                        serviceComponent = ComponentName.unflattenFromString(curService);
                        serviceInfo = AppGlobals.getPackageManager().getServiceInfo(serviceComponent, 0, this.mCurUser);
                    } catch (RemoteException | RuntimeException e) {
                        Slog.wtf(VoiceInteractionManagerService.TAG, "Bad voice interaction service name " + curService, e);
                        serviceComponent = null;
                        serviceInfo = null;
                    }
                }
                if (serviceComponent != null && serviceInfo != null) {
                    hasComponent = true;
                }
                if (VoiceInteractionManagerService.this.mUserManager.isUserUnlockingOrUnlocked(this.mCurUser)) {
                    if (hasComponent) {
                        VoiceInteractionManagerService.this.mShortcutServiceInternal.setShortcutHostPackage(VoiceInteractionManagerService.TAG, serviceComponent.getPackageName(), this.mCurUser);
                        VoiceInteractionManagerService.this.mAtmInternal.setAllowAppSwitches(VoiceInteractionManagerService.TAG, serviceInfo.applicationInfo.uid, this.mCurUser);
                    } else {
                        VoiceInteractionManagerService.this.mShortcutServiceInternal.setShortcutHostPackage(VoiceInteractionManagerService.TAG, (String) null, this.mCurUser);
                        VoiceInteractionManagerService.this.mAtmInternal.setAllowAppSwitches(VoiceInteractionManagerService.TAG, -1, this.mCurUser);
                    }
                }
                if (force || (voiceInteractionManagerServiceImpl = this.mImpl) == null || voiceInteractionManagerServiceImpl.mUser != this.mCurUser || !this.mImpl.mComponent.equals(serviceComponent)) {
                    unloadAllKeyphraseModels();
                    VoiceInteractionManagerServiceImpl voiceInteractionManagerServiceImpl2 = this.mImpl;
                    if (voiceInteractionManagerServiceImpl2 != null) {
                        voiceInteractionManagerServiceImpl2.shutdownLocked();
                    }
                    if (hasComponent) {
                        setImplLocked(new VoiceInteractionManagerServiceImpl(VoiceInteractionManagerService.this.mContext, UiThread.getHandler(), this, this.mCurUser, serviceComponent));
                        this.mImpl.startLocked();
                        return;
                    }
                    setImplLocked(null);
                }
            }
        }

        VoiceInteractionServiceInfo findAvailInteractor(int userHandle, String packageName) {
            List<ResolveInfo> available = VoiceInteractionManagerService.this.mContext.getPackageManager().queryIntentServicesAsUser(new Intent("android.service.voice.VoiceInteractionService"), 269221888, userHandle);
            int numAvailable = available.size();
            if (numAvailable == 0) {
                Slog.w(VoiceInteractionManagerService.TAG, "no available voice interaction services found for user " + userHandle);
                return null;
            }
            VoiceInteractionServiceInfo foundInfo = null;
            for (int i = 0; i < numAvailable; i++) {
                ServiceInfo cur = available.get(i).serviceInfo;
                if ((cur.applicationInfo.flags & 1) != 0) {
                    ComponentName comp = new ComponentName(cur.packageName, cur.name);
                    try {
                        VoiceInteractionServiceInfo info = new VoiceInteractionServiceInfo(VoiceInteractionManagerService.this.mContext.getPackageManager(), comp, userHandle);
                        if (info.getParseError() == null) {
                            if (packageName == null || info.getServiceInfo().packageName.equals(packageName)) {
                                if (foundInfo == null) {
                                    foundInfo = info;
                                } else {
                                    Slog.w(VoiceInteractionManagerService.TAG, "More than one voice interaction service, picking first " + new ComponentName(foundInfo.getServiceInfo().packageName, foundInfo.getServiceInfo().name) + " over " + new ComponentName(cur.packageName, cur.name));
                                }
                            }
                        } else {
                            Slog.w(VoiceInteractionManagerService.TAG, "Bad interaction service " + comp + ": " + info.getParseError());
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        Slog.w(VoiceInteractionManagerService.TAG, "Failure looking up interaction service " + comp);
                    }
                }
            }
            return foundInfo;
        }

        ComponentName getCurInteractor(int userHandle) {
            String curInteractor = Settings.Secure.getStringForUser(VoiceInteractionManagerService.this.mContext.getContentResolver(), "voice_interaction_service", userHandle);
            if (TextUtils.isEmpty(curInteractor)) {
                return null;
            }
            return ComponentName.unflattenFromString(curInteractor);
        }

        void setCurInteractor(ComponentName comp, int userHandle) {
            Settings.Secure.putStringForUser(VoiceInteractionManagerService.this.mContext.getContentResolver(), "voice_interaction_service", comp != null ? comp.flattenToShortString() : "", userHandle);
        }

        ComponentName findAvailRecognizer(String prefPackage, int userHandle) {
            List<ResolveInfo> available = VoiceInteractionManagerService.this.mContext.getPackageManager().queryIntentServicesAsUser(new Intent("android.speech.RecognitionService"), 786432, userHandle);
            int numAvailable = available.size();
            if (numAvailable == 0) {
                Slog.w(VoiceInteractionManagerService.TAG, "no available voice recognition services found for user " + userHandle);
                return null;
            }
            if (prefPackage != null) {
                for (int i = 0; i < numAvailable; i++) {
                    ServiceInfo serviceInfo = available.get(i).serviceInfo;
                    if (prefPackage.equals(serviceInfo.packageName)) {
                        return new ComponentName(serviceInfo.packageName, serviceInfo.name);
                    }
                }
            }
            if (numAvailable > 1) {
                Slog.w(VoiceInteractionManagerService.TAG, "more than one voice recognition service found, picking first");
            }
            ServiceInfo serviceInfo2 = available.get(0).serviceInfo;
            return new ComponentName(serviceInfo2.packageName, serviceInfo2.name);
        }

        ComponentName getCurRecognizer(int userHandle) {
            String curRecognizer = Settings.Secure.getStringForUser(VoiceInteractionManagerService.this.mContext.getContentResolver(), "voice_recognition_service", userHandle);
            if (TextUtils.isEmpty(curRecognizer)) {
                return null;
            }
            return ComponentName.unflattenFromString(curRecognizer);
        }

        void setCurRecognizer(ComponentName comp, int userHandle) {
            Settings.Secure.putStringForUser(VoiceInteractionManagerService.this.mContext.getContentResolver(), "voice_recognition_service", comp != null ? comp.flattenToShortString() : "", userHandle);
        }

        ComponentName getCurAssistant(int userHandle) {
            String curAssistant = Settings.Secure.getStringForUser(VoiceInteractionManagerService.this.mContext.getContentResolver(), "assistant", userHandle);
            if (TextUtils.isEmpty(curAssistant)) {
                return null;
            }
            return ComponentName.unflattenFromString(curAssistant);
        }

        void resetCurAssistant(int userHandle) {
            Settings.Secure.putStringForUser(VoiceInteractionManagerService.this.mContext.getContentResolver(), "assistant", null, userHandle);
        }

        public void showSession(IVoiceInteractionService service, Bundle args, int flags) {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService(service);
                long caller = Binder.clearCallingIdentity();
                this.mImpl.showSessionLocked(args, flags, null, null);
                Binder.restoreCallingIdentity(caller);
            }
        }

        public boolean deliverNewSession(IBinder token, IVoiceInteractionSession session, IVoiceInteractor interactor) {
            boolean deliverNewSessionLocked;
            synchronized (this) {
                if (this.mImpl == null) {
                    throw new SecurityException("deliverNewSession without running voice interaction service");
                }
                long caller = Binder.clearCallingIdentity();
                deliverNewSessionLocked = this.mImpl.deliverNewSessionLocked(token, session, interactor);
                Binder.restoreCallingIdentity(caller);
            }
            return deliverNewSessionLocked;
        }

        public boolean showSessionFromSession(IBinder token, Bundle sessionArgs, int flags) {
            synchronized (this) {
                if (this.mImpl == null) {
                    Slog.w(VoiceInteractionManagerService.TAG, "showSessionFromSession without running voice interaction service");
                    return false;
                }
                long caller = Binder.clearCallingIdentity();
                boolean showSessionLocked = this.mImpl.showSessionLocked(sessionArgs, flags, null, null);
                Binder.restoreCallingIdentity(caller);
                return showSessionLocked;
            }
        }

        public boolean hideSessionFromSession(IBinder token) {
            synchronized (this) {
                if (this.mImpl == null) {
                    Slog.w(VoiceInteractionManagerService.TAG, "hideSessionFromSession without running voice interaction service");
                    return false;
                }
                long caller = Binder.clearCallingIdentity();
                boolean hideSessionLocked = this.mImpl.hideSessionLocked();
                Binder.restoreCallingIdentity(caller);
                return hideSessionLocked;
            }
        }

        public int startVoiceActivity(IBinder token, Intent intent, String resolvedType) {
            synchronized (this) {
                if (this.mImpl == null) {
                    Slog.w(VoiceInteractionManagerService.TAG, "startVoiceActivity without running voice interaction service");
                    return -96;
                }
                int callingPid = Binder.getCallingPid();
                int callingUid = Binder.getCallingUid();
                long caller = Binder.clearCallingIdentity();
                int startVoiceActivityLocked = this.mImpl.startVoiceActivityLocked(callingPid, callingUid, token, intent, resolvedType);
                Binder.restoreCallingIdentity(caller);
                return startVoiceActivityLocked;
            }
        }

        public int startAssistantActivity(IBinder token, Intent intent, String resolvedType) {
            synchronized (this) {
                if (this.mImpl == null) {
                    Slog.w(VoiceInteractionManagerService.TAG, "startAssistantActivity without running voice interaction service");
                    return -96;
                }
                int callingPid = Binder.getCallingPid();
                int callingUid = Binder.getCallingUid();
                long caller = Binder.clearCallingIdentity();
                int startAssistantActivityLocked = this.mImpl.startAssistantActivityLocked(callingPid, callingUid, token, intent, resolvedType);
                Binder.restoreCallingIdentity(caller);
                return startAssistantActivityLocked;
            }
        }

        public void requestDirectActions(IBinder token, int taskId, IBinder assistToken, RemoteCallback cancellationCallback, RemoteCallback resultCallback) {
            synchronized (this) {
                if (this.mImpl == null) {
                    Slog.w(VoiceInteractionManagerService.TAG, "requestDirectActions without running voice interaction service");
                    resultCallback.sendResult((Bundle) null);
                    return;
                }
                long caller = Binder.clearCallingIdentity();
                this.mImpl.requestDirectActionsLocked(token, taskId, assistToken, cancellationCallback, resultCallback);
                Binder.restoreCallingIdentity(caller);
            }
        }

        public void performDirectAction(IBinder token, String actionId, Bundle arguments, int taskId, IBinder assistToken, RemoteCallback cancellationCallback, RemoteCallback resultCallback) {
            synchronized (this) {
                try {
                } catch (Throwable th) {
                    th = th;
                }
                try {
                    if (this.mImpl == null) {
                        Slog.w(VoiceInteractionManagerService.TAG, "performDirectAction without running voice interaction service");
                        resultCallback.sendResult((Bundle) null);
                        return;
                    }
                    long caller = Binder.clearCallingIdentity();
                    this.mImpl.performDirectActionLocked(token, actionId, arguments, taskId, assistToken, cancellationCallback, resultCallback);
                    Binder.restoreCallingIdentity(caller);
                } catch (Throwable th2) {
                    th = th2;
                    throw th;
                }
            }
        }

        public void setKeepAwake(IBinder token, boolean keepAwake) {
            synchronized (this) {
                if (this.mImpl == null) {
                    Slog.w(VoiceInteractionManagerService.TAG, "setKeepAwake without running voice interaction service");
                    return;
                }
                long caller = Binder.clearCallingIdentity();
                this.mImpl.setKeepAwakeLocked(token, keepAwake);
                Binder.restoreCallingIdentity(caller);
            }
        }

        public void closeSystemDialogs(IBinder token) {
            synchronized (this) {
                if (this.mImpl == null) {
                    Slog.w(VoiceInteractionManagerService.TAG, "closeSystemDialogs without running voice interaction service");
                    return;
                }
                long caller = Binder.clearCallingIdentity();
                this.mImpl.closeSystemDialogsLocked(token);
                Binder.restoreCallingIdentity(caller);
            }
        }

        public void finish(IBinder token) {
            synchronized (this) {
                if (this.mImpl == null) {
                    Slog.w(VoiceInteractionManagerService.TAG, "finish without running voice interaction service");
                    return;
                }
                long caller = Binder.clearCallingIdentity();
                this.mImpl.finishLocked(token, false);
                Binder.restoreCallingIdentity(caller);
            }
        }

        public void setDisabledShowContext(int flags) {
            synchronized (this) {
                if (this.mImpl == null) {
                    Slog.w(VoiceInteractionManagerService.TAG, "setDisabledShowContext without running voice interaction service");
                    return;
                }
                int callingUid = Binder.getCallingUid();
                long caller = Binder.clearCallingIdentity();
                this.mImpl.setDisabledShowContextLocked(callingUid, flags);
                Binder.restoreCallingIdentity(caller);
            }
        }

        public int getDisabledShowContext() {
            synchronized (this) {
                if (this.mImpl == null) {
                    Slog.w(VoiceInteractionManagerService.TAG, "getDisabledShowContext without running voice interaction service");
                    return 0;
                }
                int callingUid = Binder.getCallingUid();
                long caller = Binder.clearCallingIdentity();
                int disabledShowContextLocked = this.mImpl.getDisabledShowContextLocked(callingUid);
                Binder.restoreCallingIdentity(caller);
                return disabledShowContextLocked;
            }
        }

        public int getUserDisabledShowContext() {
            synchronized (this) {
                if (this.mImpl == null) {
                    Slog.w(VoiceInteractionManagerService.TAG, "getUserDisabledShowContext without running voice interaction service");
                    return 0;
                }
                int callingUid = Binder.getCallingUid();
                long caller = Binder.clearCallingIdentity();
                int userDisabledShowContextLocked = this.mImpl.getUserDisabledShowContextLocked(callingUid);
                Binder.restoreCallingIdentity(caller);
                return userDisabledShowContextLocked;
            }
        }

        public SoundTrigger.KeyphraseSoundModel getKeyphraseSoundModel(int keyphraseId, String bcp47Locale) {
            enforceCallingPermission("android.permission.MANAGE_VOICE_KEYPHRASES");
            if (bcp47Locale == null) {
                throw new IllegalArgumentException("Illegal argument(s) in getKeyphraseSoundModel");
            }
            int callingUid = UserHandle.getCallingUserId();
            long caller = Binder.clearCallingIdentity();
            try {
                return VoiceInteractionManagerService.this.mDbHelper.getKeyphraseSoundModel(keyphraseId, callingUid, bcp47Locale);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        public int updateKeyphraseSoundModel(SoundTrigger.KeyphraseSoundModel model) {
            enforceCallingPermission("android.permission.MANAGE_VOICE_KEYPHRASES");
            if (model == null) {
                throw new IllegalArgumentException("Model must not be null");
            }
            long caller = Binder.clearCallingIdentity();
            try {
                if (VoiceInteractionManagerService.this.mDbHelper.updateKeyphraseSoundModel(model)) {
                    synchronized (this) {
                        if (this.mImpl != null && this.mImpl.mService != null) {
                            this.mImpl.notifySoundModelsChangedLocked();
                        }
                    }
                    return 0;
                }
                return Integer.MIN_VALUE;
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        public int deleteKeyphraseSoundModel(int keyphraseId, String bcp47Locale) {
            enforceCallingPermission("android.permission.MANAGE_VOICE_KEYPHRASES");
            if (bcp47Locale == null) {
                throw new IllegalArgumentException("Illegal argument(s) in deleteKeyphraseSoundModel");
            }
            int callingUid = UserHandle.getCallingUserId();
            long caller = Binder.clearCallingIdentity();
            try {
                int unloadStatus = VoiceInteractionManagerService.this.mSoundTriggerInternal.unloadKeyphraseModel(keyphraseId);
                if (unloadStatus != 0) {
                    Slog.w(VoiceInteractionManagerService.TAG, "Unable to unload keyphrase sound model:" + unloadStatus);
                }
                boolean deleted = VoiceInteractionManagerService.this.mDbHelper.deleteKeyphraseSoundModel(keyphraseId, callingUid, bcp47Locale);
                int i = deleted ? 0 : Integer.MIN_VALUE;
                if (deleted) {
                    synchronized (this) {
                        if (this.mImpl != null && this.mImpl.mService != null) {
                            this.mImpl.notifySoundModelsChangedLocked();
                        }
                        VoiceInteractionManagerService.this.mLoadedKeyphraseIds.remove(Integer.valueOf(keyphraseId));
                    }
                }
                Binder.restoreCallingIdentity(caller);
                return i;
            } catch (Throwable th) {
                if (0 != 0) {
                    synchronized (this) {
                        if (this.mImpl != null && this.mImpl.mService != null) {
                            this.mImpl.notifySoundModelsChangedLocked();
                        }
                        VoiceInteractionManagerService.this.mLoadedKeyphraseIds.remove(Integer.valueOf(keyphraseId));
                    }
                }
                Binder.restoreCallingIdentity(caller);
                throw th;
            }
        }

        public boolean isEnrolledForKeyphrase(IVoiceInteractionService service, int keyphraseId, String bcp47Locale) {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService(service);
            }
            if (bcp47Locale == null) {
                throw new IllegalArgumentException("Illegal argument(s) in isEnrolledForKeyphrase");
            }
            int callingUid = UserHandle.getCallingUserId();
            long caller = Binder.clearCallingIdentity();
            try {
                SoundTrigger.KeyphraseSoundModel model = VoiceInteractionManagerService.this.mDbHelper.getKeyphraseSoundModel(keyphraseId, callingUid, bcp47Locale);
                return model != null;
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        public SoundTrigger.ModuleProperties getDspModuleProperties(IVoiceInteractionService service) {
            SoundTrigger.ModuleProperties moduleProperties;
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService(service);
                long caller = Binder.clearCallingIdentity();
                moduleProperties = VoiceInteractionManagerService.this.mSoundTriggerInternal.getModuleProperties();
                Binder.restoreCallingIdentity(caller);
            }
            return moduleProperties;
        }

        public int startRecognition(IVoiceInteractionService service, int keyphraseId, String bcp47Locale, IRecognitionStatusCallback callback, SoundTrigger.RecognitionConfig recognitionConfig) {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService(service);
                if (callback == null || recognitionConfig == null || bcp47Locale == null) {
                    throw new IllegalArgumentException("Illegal argument(s) in startRecognition");
                }
            }
            int callingUid = UserHandle.getCallingUserId();
            long caller = Binder.clearCallingIdentity();
            try {
                SoundTrigger.KeyphraseSoundModel soundModel = VoiceInteractionManagerService.this.mDbHelper.getKeyphraseSoundModel(keyphraseId, callingUid, bcp47Locale);
                if (soundModel != null && soundModel.uuid != null && soundModel.keyphrases != null) {
                    synchronized (this) {
                        VoiceInteractionManagerService.this.mLoadedKeyphraseIds.add(Integer.valueOf(keyphraseId));
                    }
                    return VoiceInteractionManagerService.this.mSoundTriggerInternal.startRecognition(keyphraseId, soundModel, callback, recognitionConfig);
                }
                Slog.w(VoiceInteractionManagerService.TAG, "No matching sound model found in startRecognition");
                return Integer.MIN_VALUE;
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        public int stopRecognition(IVoiceInteractionService service, int keyphraseId, IRecognitionStatusCallback callback) {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService(service);
            }
            long caller = Binder.clearCallingIdentity();
            try {
                return VoiceInteractionManagerService.this.mSoundTriggerInternal.stopRecognition(keyphraseId, callback);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public synchronized void unloadAllKeyphraseModels() {
            for (int i = 0; i < VoiceInteractionManagerService.this.mLoadedKeyphraseIds.size(); i++) {
                long caller = Binder.clearCallingIdentity();
                try {
                    int status = VoiceInteractionManagerService.this.mSoundTriggerInternal.unloadKeyphraseModel(VoiceInteractionManagerService.this.mLoadedKeyphraseIds.valueAt(i).intValue());
                    if (status != 0) {
                        try {
                            Slog.w(VoiceInteractionManagerService.TAG, "Failed to unload keyphrase " + VoiceInteractionManagerService.this.mLoadedKeyphraseIds.valueAt(i) + ":" + status);
                        } catch (Throwable th) {
                            th = th;
                            Binder.restoreCallingIdentity(caller);
                            throw th;
                        }
                    }
                    Binder.restoreCallingIdentity(caller);
                } catch (Throwable th2) {
                    th = th2;
                }
            }
            VoiceInteractionManagerService.this.mLoadedKeyphraseIds.clear();
        }

        public ComponentName getActiveServiceComponentName() {
            ComponentName componentName;
            enforceCallingPermission("android.permission.ACCESS_VOICE_INTERACTION_SERVICE");
            synchronized (this) {
                componentName = this.mImpl != null ? this.mImpl.mComponent : null;
            }
            return componentName;
        }

        public boolean showSessionForActiveService(Bundle args, int sourceFlags, IVoiceInteractionSessionShowCallback showCallback, IBinder activityToken) {
            enforceCallingPermission("android.permission.ACCESS_VOICE_INTERACTION_SERVICE");
            synchronized (this) {
                if (this.mImpl == null) {
                    Slog.w(VoiceInteractionManagerService.TAG, "showSessionForActiveService without running voice interactionservice");
                    return false;
                }
                long caller = Binder.clearCallingIdentity();
                boolean showSessionLocked = this.mImpl.showSessionLocked(args, sourceFlags | 1 | 2, showCallback, activityToken);
                Binder.restoreCallingIdentity(caller);
                return showSessionLocked;
            }
        }

        public void hideCurrentSession() throws RemoteException {
            enforceCallingPermission("android.permission.ACCESS_VOICE_INTERACTION_SERVICE");
            synchronized (this) {
                if (this.mImpl == null) {
                    return;
                }
                long caller = Binder.clearCallingIdentity();
                if (this.mImpl.mActiveSession != null && this.mImpl.mActiveSession.mSession != null) {
                    try {
                        this.mImpl.mActiveSession.mSession.closeSystemDialogs();
                    } catch (RemoteException e) {
                        Log.w(VoiceInteractionManagerService.TAG, "Failed to call closeSystemDialogs", e);
                    }
                }
                Binder.restoreCallingIdentity(caller);
            }
        }

        public void launchVoiceAssistFromKeyguard() {
            enforceCallingPermission("android.permission.ACCESS_VOICE_INTERACTION_SERVICE");
            synchronized (this) {
                if (this.mImpl == null) {
                    Slog.w(VoiceInteractionManagerService.TAG, "launchVoiceAssistFromKeyguard without running voice interactionservice");
                    return;
                }
                long caller = Binder.clearCallingIdentity();
                this.mImpl.launchVoiceAssistFromKeyguard();
                Binder.restoreCallingIdentity(caller);
            }
        }

        public boolean isSessionRunning() {
            boolean z;
            enforceCallingPermission("android.permission.ACCESS_VOICE_INTERACTION_SERVICE");
            synchronized (this) {
                z = (this.mImpl == null || this.mImpl.mActiveSession == null) ? false : true;
            }
            return z;
        }

        public boolean activeServiceSupportsAssist() {
            boolean z;
            enforceCallingPermission("android.permission.ACCESS_VOICE_INTERACTION_SERVICE");
            synchronized (this) {
                z = (this.mImpl == null || this.mImpl.mInfo == null || !this.mImpl.mInfo.getSupportsAssist()) ? false : true;
            }
            return z;
        }

        public boolean activeServiceSupportsLaunchFromKeyguard() throws RemoteException {
            boolean z;
            enforceCallingPermission("android.permission.ACCESS_VOICE_INTERACTION_SERVICE");
            synchronized (this) {
                z = (this.mImpl == null || this.mImpl.mInfo == null || !this.mImpl.mInfo.getSupportsLaunchFromKeyguard()) ? false : true;
            }
            return z;
        }

        public void onLockscreenShown() {
            enforceCallingPermission("android.permission.ACCESS_VOICE_INTERACTION_SERVICE");
            synchronized (this) {
                if (this.mImpl == null) {
                    return;
                }
                long caller = Binder.clearCallingIdentity();
                if (this.mImpl.mActiveSession != null && this.mImpl.mActiveSession.mSession != null) {
                    try {
                        this.mImpl.mActiveSession.mSession.onLockscreenShown();
                    } catch (RemoteException e) {
                        Log.w(VoiceInteractionManagerService.TAG, "Failed to call onLockscreenShown", e);
                    }
                }
                Binder.restoreCallingIdentity(caller);
            }
        }

        public void registerVoiceInteractionSessionListener(IVoiceInteractionSessionListener listener) {
            enforceCallingPermission("android.permission.ACCESS_VOICE_INTERACTION_SERVICE");
            synchronized (this) {
                VoiceInteractionManagerService.this.mVoiceInteractionSessionListeners.register(listener);
            }
        }

        public void getActiveServiceSupportedActions(List<String> voiceActions, IVoiceActionCheckCallback callback) {
            enforceCallingPermission("android.permission.ACCESS_VOICE_INTERACTION_SERVICE");
            synchronized (this) {
                if (this.mImpl == null) {
                    try {
                        callback.onComplete((List) null);
                    } catch (RemoteException e) {
                    }
                    return;
                }
                long caller = Binder.clearCallingIdentity();
                this.mImpl.getActiveServiceSupportedActions(voiceActions, callback);
                Binder.restoreCallingIdentity(caller);
            }
        }

        public void onSessionShown() {
            synchronized (this) {
                int size = VoiceInteractionManagerService.this.mVoiceInteractionSessionListeners.beginBroadcast();
                for (int i = 0; i < size; i++) {
                    IVoiceInteractionSessionListener listener = VoiceInteractionManagerService.this.mVoiceInteractionSessionListeners.getBroadcastItem(i);
                    try {
                        listener.onVoiceSessionShown();
                    } catch (RemoteException e) {
                        Slog.e(VoiceInteractionManagerService.TAG, "Error delivering voice interaction open event.", e);
                    }
                }
                VoiceInteractionManagerService.this.mVoiceInteractionSessionListeners.finishBroadcast();
            }
        }

        public void onSessionHidden() {
            synchronized (this) {
                int size = VoiceInteractionManagerService.this.mVoiceInteractionSessionListeners.beginBroadcast();
                for (int i = 0; i < size; i++) {
                    IVoiceInteractionSessionListener listener = VoiceInteractionManagerService.this.mVoiceInteractionSessionListeners.getBroadcastItem(i);
                    try {
                        listener.onVoiceSessionHidden();
                    } catch (RemoteException e) {
                        Slog.e(VoiceInteractionManagerService.TAG, "Error delivering voice interaction closed event.", e);
                    }
                }
                VoiceInteractionManagerService.this.mVoiceInteractionSessionListeners.finishBroadcast();
            }
        }

        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (DumpUtils.checkDumpPermission(VoiceInteractionManagerService.this.mContext, VoiceInteractionManagerService.TAG, pw)) {
                synchronized (this) {
                    pw.println("VOICE INTERACTION MANAGER (dumpsys voiceinteraction)");
                    pw.println("  mEnableService: " + this.mEnableService);
                    if (this.mImpl == null) {
                        pw.println("  (No active implementation)");
                        return;
                    }
                    this.mImpl.dumpLocked(fd, pw, args);
                    VoiceInteractionManagerService.this.mSoundTriggerInternal.dump(fd, pw, args);
                }
            }
        }

        public void setUiHints(IVoiceInteractionService service, Bundle hints) {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService(service);
                int size = VoiceInteractionManagerService.this.mVoiceInteractionSessionListeners.beginBroadcast();
                for (int i = 0; i < size; i++) {
                    IVoiceInteractionSessionListener listener = VoiceInteractionManagerService.this.mVoiceInteractionSessionListeners.getBroadcastItem(i);
                    try {
                        listener.onSetUiHints(hints);
                    } catch (RemoteException e) {
                        Slog.e(VoiceInteractionManagerService.TAG, "Error delivering UI hints.", e);
                    }
                }
                VoiceInteractionManagerService.this.mVoiceInteractionSessionListeners.finishBroadcast();
            }
        }

        private void enforceCallingPermission(String permission) {
            if (VoiceInteractionManagerService.this.mContext.checkCallingOrSelfPermission(permission) != 0) {
                throw new SecurityException("Caller does not hold the permission " + permission);
            }
        }

        private void enforceIsCurrentVoiceInteractionService(IVoiceInteractionService service) {
            VoiceInteractionManagerServiceImpl voiceInteractionManagerServiceImpl = this.mImpl;
            if (voiceInteractionManagerServiceImpl == null || voiceInteractionManagerServiceImpl.mService == null || service.asBinder() != this.mImpl.mService.asBinder()) {
                throw new SecurityException("Caller is not the current voice interaction service");
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void setImplLocked(VoiceInteractionManagerServiceImpl impl) {
            this.mImpl = impl;
            VoiceInteractionManagerService.this.mAtmInternal.notifyActiveVoiceInteractionServiceChanged(getActiveServiceComponentName());
        }

        /* loaded from: classes2.dex */
        class RoleObserver implements OnRoleHoldersChangedListener {
            private PackageManager mPm;
            private RoleManager mRm;

            RoleObserver(Executor executor) {
                this.mPm = VoiceInteractionManagerService.this.mContext.getPackageManager();
                this.mRm = (RoleManager) VoiceInteractionManagerService.this.mContext.getSystemService(RoleManager.class);
                this.mRm.addOnRoleHoldersChangedListenerAsUser(executor, this, UserHandle.ALL);
                if (this.mRm.isRoleAvailable("android.app.role.ASSISTANT")) {
                    UserHandle currentUser = UserHandle.of(((ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class)).getCurrentUserId());
                    onRoleHoldersChanged("android.app.role.ASSISTANT", currentUser);
                }
            }

            private String getDefaultRecognizer(UserHandle user) {
                ResolveInfo resolveInfo = this.mPm.resolveServiceAsUser(new Intent("android.speech.RecognitionService"), 128, user.getIdentifier());
                if (resolveInfo == null || resolveInfo.serviceInfo == null) {
                    Log.w(VoiceInteractionManagerService.TAG, "Unable to resolve default voice recognition service.");
                    return "";
                }
                return new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name).flattenToShortString();
            }

            public void onRoleHoldersChanged(String roleName, UserHandle user) {
                if (!roleName.equals("android.app.role.ASSISTANT")) {
                    return;
                }
                List<String> roleHolders = this.mRm.getRoleHoldersAsUser(roleName, user);
                int userId = user.getIdentifier();
                if (roleHolders.isEmpty()) {
                    Settings.Secure.putStringForUser(VoiceInteractionManagerService.this.getContext().getContentResolver(), "assistant", "", userId);
                    Settings.Secure.putStringForUser(VoiceInteractionManagerService.this.getContext().getContentResolver(), "voice_interaction_service", "", userId);
                    Settings.Secure.putStringForUser(VoiceInteractionManagerService.this.getContext().getContentResolver(), "voice_recognition_service", getDefaultRecognizer(user), userId);
                    return;
                }
                String pkg = roleHolders.get(0);
                List<ResolveInfo> services = this.mPm.queryIntentServicesAsUser(new Intent("android.service.voice.VoiceInteractionService").setPackage(pkg), 786560, userId);
                for (ResolveInfo resolveInfo : services) {
                    ServiceInfo serviceInfo = resolveInfo.serviceInfo;
                    VoiceInteractionServiceInfo voiceInteractionServiceInfo = new VoiceInteractionServiceInfo(this.mPm, serviceInfo);
                    if (voiceInteractionServiceInfo.getSupportsAssist()) {
                        String serviceComponentName = serviceInfo.getComponentName().flattenToShortString();
                        String serviceRecognizerName = new ComponentName(pkg, voiceInteractionServiceInfo.getRecognitionService()).flattenToShortString();
                        Settings.Secure.putStringForUser(VoiceInteractionManagerService.this.getContext().getContentResolver(), "assistant", serviceComponentName, userId);
                        Settings.Secure.putStringForUser(VoiceInteractionManagerService.this.getContext().getContentResolver(), "voice_interaction_service", serviceComponentName, userId);
                        Settings.Secure.putStringForUser(VoiceInteractionManagerService.this.getContext().getContentResolver(), "voice_recognition_service", serviceRecognizerName, userId);
                        return;
                    }
                }
                List<ResolveInfo> activities = this.mPm.queryIntentActivitiesAsUser(new Intent("android.intent.action.ASSIST").setPackage(pkg), 851968, userId);
                Iterator<ResolveInfo> it = activities.iterator();
                if (it.hasNext()) {
                    ResolveInfo resolveInfo2 = it.next();
                    ActivityInfo activityInfo = resolveInfo2.activityInfo;
                    Settings.Secure.putStringForUser(VoiceInteractionManagerService.this.getContext().getContentResolver(), "assistant", activityInfo.getComponentName().flattenToShortString(), userId);
                    Settings.Secure.putStringForUser(VoiceInteractionManagerService.this.getContext().getContentResolver(), "voice_interaction_service", "", userId);
                    Settings.Secure.putStringForUser(VoiceInteractionManagerService.this.getContext().getContentResolver(), "voice_recognition_service", getDefaultRecognizer(user), userId);
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        /* loaded from: classes2.dex */
        public class SettingsObserver extends ContentObserver {
            SettingsObserver(Handler handler) {
                super(handler);
                ContentResolver resolver = VoiceInteractionManagerService.this.mContext.getContentResolver();
                resolver.registerContentObserver(Settings.Secure.getUriFor("voice_interaction_service"), false, this, -1);
            }

            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    VoiceInteractionManagerServiceStub.this.switchImplementationIfNeededLocked(false);
                }
            }
        }

        /* renamed from: com.android.server.voiceinteraction.VoiceInteractionManagerService$VoiceInteractionManagerServiceStub$2  reason: invalid class name */
        /* loaded from: classes2.dex */
        class AnonymousClass2 extends PackageMonitor {
            AnonymousClass2() {
            }

            public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
                boolean hitRec;
                boolean hitRec2;
                int userHandle = UserHandle.getUserId(uid);
                ComponentName curInteractor = VoiceInteractionManagerServiceStub.this.getCurInteractor(userHandle);
                ComponentName curRecognizer = VoiceInteractionManagerServiceStub.this.getCurRecognizer(userHandle);
                int length = packages.length;
                int i = 0;
                while (true) {
                    if (i >= length) {
                        hitRec = false;
                        hitRec2 = false;
                        break;
                    }
                    String pkg = packages[i];
                    if (curInteractor != null && pkg.equals(curInteractor.getPackageName())) {
                        hitRec = false;
                        hitRec2 = true;
                        break;
                    } else if (curRecognizer == null || !pkg.equals(curRecognizer.getPackageName())) {
                        i++;
                    } else {
                        hitRec = true;
                        hitRec2 = false;
                        break;
                    }
                }
                if (hitRec2 && doit) {
                    synchronized (VoiceInteractionManagerServiceStub.this) {
                        Slog.i(VoiceInteractionManagerService.TAG, "Force stopping current voice interactor: " + VoiceInteractionManagerServiceStub.this.getCurInteractor(userHandle));
                        VoiceInteractionManagerServiceStub.this.unloadAllKeyphraseModels();
                        if (VoiceInteractionManagerServiceStub.this.mImpl != null) {
                            VoiceInteractionManagerServiceStub.this.mImpl.shutdownLocked();
                            VoiceInteractionManagerServiceStub.this.setImplLocked(null);
                        }
                        VoiceInteractionManagerServiceStub.this.setCurInteractor(null, userHandle);
                        VoiceInteractionManagerServiceStub.this.setCurRecognizer(null, userHandle);
                        VoiceInteractionManagerServiceStub.this.resetCurAssistant(userHandle);
                        VoiceInteractionManagerServiceStub.this.initForUser(userHandle);
                        VoiceInteractionManagerServiceStub.this.switchImplementationIfNeededLocked(true);
                        Context context = VoiceInteractionManagerService.this.getContext();
                        ((RoleManager) context.getSystemService(RoleManager.class)).clearRoleHoldersAsUser("android.app.role.ASSISTANT", 0, UserHandle.of(userHandle), context.getMainExecutor(), new Consumer() { // from class: com.android.server.voiceinteraction.-$$Lambda$VoiceInteractionManagerService$VoiceInteractionManagerServiceStub$2$_YjGqp96fW1i83gthgQe_rVHY5s
                            @Override // java.util.function.Consumer
                            public final void accept(Object obj) {
                                VoiceInteractionManagerService.VoiceInteractionManagerServiceStub.AnonymousClass2.lambda$onHandleForceStop$0((Boolean) obj);
                            }
                        });
                    }
                } else if (hitRec && doit) {
                    synchronized (VoiceInteractionManagerServiceStub.this) {
                        Slog.i(VoiceInteractionManagerService.TAG, "Force stopping current voice recognizer: " + VoiceInteractionManagerServiceStub.this.getCurRecognizer(userHandle));
                        VoiceInteractionManagerServiceStub.this.initSimpleRecognizer(null, userHandle);
                    }
                }
                return hitRec2 || hitRec;
            }

            /* JADX INFO: Access modifiers changed from: package-private */
            public static /* synthetic */ void lambda$onHandleForceStop$0(Boolean successful) {
                if (!successful.booleanValue()) {
                    Slog.e(VoiceInteractionManagerService.TAG, "Failed to clear default assistant for force stop");
                }
            }

            public void onHandleUserStop(Intent intent, int userHandle) {
            }

            public void onPackageModified(String pkgName) {
                if (VoiceInteractionManagerServiceStub.this.mCurUser != getChangingUserId() || isPackageAppearing(pkgName) != 0) {
                    return;
                }
                VoiceInteractionManagerServiceStub voiceInteractionManagerServiceStub = VoiceInteractionManagerServiceStub.this;
                ComponentName curInteractor = voiceInteractionManagerServiceStub.getCurInteractor(voiceInteractionManagerServiceStub.mCurUser);
                if (curInteractor == null) {
                    VoiceInteractionManagerServiceStub voiceInteractionManagerServiceStub2 = VoiceInteractionManagerServiceStub.this;
                    VoiceInteractionServiceInfo availInteractorInfo = voiceInteractionManagerServiceStub2.findAvailInteractor(voiceInteractionManagerServiceStub2.mCurUser, pkgName);
                    if (availInteractorInfo != null) {
                        ComponentName availInteractor = new ComponentName(availInteractorInfo.getServiceInfo().packageName, availInteractorInfo.getServiceInfo().name);
                        VoiceInteractionManagerServiceStub voiceInteractionManagerServiceStub3 = VoiceInteractionManagerServiceStub.this;
                        voiceInteractionManagerServiceStub3.setCurInteractor(availInteractor, voiceInteractionManagerServiceStub3.mCurUser);
                        VoiceInteractionManagerServiceStub voiceInteractionManagerServiceStub4 = VoiceInteractionManagerServiceStub.this;
                        if (voiceInteractionManagerServiceStub4.getCurRecognizer(voiceInteractionManagerServiceStub4.mCurUser) == null && availInteractorInfo.getRecognitionService() != null) {
                            VoiceInteractionManagerServiceStub.this.setCurRecognizer(new ComponentName(availInteractorInfo.getServiceInfo().packageName, availInteractorInfo.getRecognitionService()), VoiceInteractionManagerServiceStub.this.mCurUser);
                        }
                    }
                } else if (didSomePackagesChange()) {
                    if (pkgName.equals(curInteractor.getPackageName())) {
                        VoiceInteractionManagerServiceStub.this.switchImplementationIfNeeded(true);
                    }
                } else if (isComponentModified(curInteractor.getClassName())) {
                    VoiceInteractionManagerServiceStub.this.switchImplementationIfNeeded(true);
                }
            }

            public void onSomePackagesChanged() {
                ComponentName curRecognizer;
                int userHandle = getChangingUserId();
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    ComponentName curInteractor = VoiceInteractionManagerServiceStub.this.getCurInteractor(userHandle);
                    ComponentName curRecognizer2 = VoiceInteractionManagerServiceStub.this.getCurRecognizer(userHandle);
                    ComponentName curAssistant = VoiceInteractionManagerServiceStub.this.getCurAssistant(userHandle);
                    if (curRecognizer2 == null) {
                        if (anyPackagesAppearing() && (curRecognizer = VoiceInteractionManagerServiceStub.this.findAvailRecognizer(null, userHandle)) != null) {
                            VoiceInteractionManagerServiceStub.this.setCurRecognizer(curRecognizer, userHandle);
                        }
                    } else if (curInteractor != null) {
                        if (isPackageDisappearing(curInteractor.getPackageName()) == 3) {
                            VoiceInteractionManagerServiceStub.this.setCurInteractor(null, userHandle);
                            VoiceInteractionManagerServiceStub.this.setCurRecognizer(null, userHandle);
                            VoiceInteractionManagerServiceStub.this.resetCurAssistant(userHandle);
                            VoiceInteractionManagerServiceStub.this.initForUser(userHandle);
                            return;
                        }
                        if (isPackageAppearing(curInteractor.getPackageName()) != 0 && VoiceInteractionManagerServiceStub.this.mImpl != null && curInteractor.getPackageName().equals(VoiceInteractionManagerServiceStub.this.mImpl.mComponent.getPackageName())) {
                            VoiceInteractionManagerServiceStub.this.switchImplementationIfNeededLocked(true);
                        }
                    } else if (curAssistant != null && isPackageDisappearing(curAssistant.getPackageName()) == 3) {
                        VoiceInteractionManagerServiceStub.this.setCurInteractor(null, userHandle);
                        VoiceInteractionManagerServiceStub.this.setCurRecognizer(null, userHandle);
                        VoiceInteractionManagerServiceStub.this.resetCurAssistant(userHandle);
                        VoiceInteractionManagerServiceStub.this.initForUser(userHandle);
                    } else {
                        int change = isPackageDisappearing(curRecognizer2.getPackageName());
                        if (change != 3 && change != 2) {
                            if (isPackageModified(curRecognizer2.getPackageName())) {
                                VoiceInteractionManagerServiceStub.this.setCurRecognizer(VoiceInteractionManagerServiceStub.this.findAvailRecognizer(curRecognizer2.getPackageName(), userHandle), userHandle);
                            }
                        }
                        VoiceInteractionManagerServiceStub.this.setCurRecognizer(VoiceInteractionManagerServiceStub.this.findAvailRecognizer(null, userHandle), userHandle);
                    }
                }
            }
        }
    }
}
