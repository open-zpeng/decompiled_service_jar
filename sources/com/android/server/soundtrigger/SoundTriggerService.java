package com.android.server.soundtrigger;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.hardware.broadcastradio.V2_0.IdentifierType;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.soundtrigger.ISoundTriggerDetectionService;
import android.media.soundtrigger.ISoundTriggerDetectionServiceClient;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.ISoundTriggerService;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.SystemService;
import com.android.server.job.controllers.JobStatus;
import com.android.server.soundtrigger.SoundTriggerService;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
/* loaded from: classes.dex */
public class SoundTriggerService extends SystemService {
    private static final boolean DEBUG = true;
    private static final String TAG = "SoundTriggerService";
    private PendingIntent.OnFinished mCallbackCompletedHandler;
    private final TreeMap<UUID, IRecognitionStatusCallback> mCallbacks;
    private Object mCallbacksLock;
    final Context mContext;
    private SoundTriggerDbHelper mDbHelper;
    private final TreeMap<UUID, SoundTrigger.SoundModel> mLoadedModels;
    private final LocalSoundTriggerService mLocalSoundTriggerService;
    private Object mLock;
    @GuardedBy("mLock")
    private final ArrayMap<String, NumOps> mNumOpsPerPackage;
    private final SoundTriggerServiceStub mServiceStub;
    private SoundTriggerHelper mSoundTriggerHelper;
    private PowerManager.WakeLock mWakelock;

    public SoundTriggerService(Context context) {
        super(context);
        this.mNumOpsPerPackage = new ArrayMap<>();
        this.mCallbackCompletedHandler = new PendingIntent.OnFinished() { // from class: com.android.server.soundtrigger.SoundTriggerService.1
            @Override // android.app.PendingIntent.OnFinished
            public void onSendFinished(PendingIntent pendingIntent, Intent intent, int resultCode, String resultData, Bundle resultExtras) {
                synchronized (SoundTriggerService.this.mCallbacksLock) {
                    SoundTriggerService.this.mWakelock.release();
                }
            }
        };
        this.mContext = context;
        this.mServiceStub = new SoundTriggerServiceStub();
        this.mLocalSoundTriggerService = new LocalSoundTriggerService(context);
        this.mLoadedModels = new TreeMap<>();
        this.mCallbacksLock = new Object();
        this.mCallbacks = new TreeMap<>();
        this.mLock = new Object();
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishBinderService("soundtrigger", this.mServiceStub);
        publishLocalService(SoundTriggerInternal.class, this.mLocalSoundTriggerService);
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (500 == phase) {
            initSoundTriggerHelper();
            this.mLocalSoundTriggerService.setSoundTriggerHelper(this.mSoundTriggerHelper);
        } else if (600 == phase) {
            this.mDbHelper = new SoundTriggerDbHelper(this.mContext);
        }
    }

    @Override // com.android.server.SystemService
    public void onStartUser(int userHandle) {
    }

    @Override // com.android.server.SystemService
    public void onSwitchUser(int userHandle) {
    }

    private synchronized void initSoundTriggerHelper() {
        if (this.mSoundTriggerHelper == null) {
            this.mSoundTriggerHelper = new SoundTriggerHelper(this.mContext);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized boolean isInitialized() {
        if (this.mSoundTriggerHelper == null) {
            Slog.e(TAG, "SoundTriggerHelper not initialized.");
            return false;
        }
        return true;
    }

    /* loaded from: classes.dex */
    class SoundTriggerServiceStub extends ISoundTriggerService.Stub {
        SoundTriggerServiceStub() {
        }

        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (RuntimeException e) {
                if (!(e instanceof SecurityException)) {
                    Slog.wtf(SoundTriggerService.TAG, "SoundTriggerService Crash", e);
                }
                throw e;
            }
        }

        public int startRecognition(ParcelUuid parcelUuid, IRecognitionStatusCallback callback, SoundTrigger.RecognitionConfig config) {
            SoundTriggerService.this.enforceCallingPermission("android.permission.MANAGE_SOUND_TRIGGER");
            if (SoundTriggerService.this.isInitialized()) {
                Slog.i(SoundTriggerService.TAG, "startRecognition(): Uuid : " + parcelUuid);
                SoundTrigger.GenericSoundModel model = getSoundModel(parcelUuid);
                if (model != null) {
                    return SoundTriggerService.this.mSoundTriggerHelper.startGenericRecognition(parcelUuid.getUuid(), model, callback, config);
                }
                Slog.e(SoundTriggerService.TAG, "Null model in database for id: " + parcelUuid);
                return Integer.MIN_VALUE;
            }
            return Integer.MIN_VALUE;
        }

        public int stopRecognition(ParcelUuid parcelUuid, IRecognitionStatusCallback callback) {
            SoundTriggerService.this.enforceCallingPermission("android.permission.MANAGE_SOUND_TRIGGER");
            Slog.i(SoundTriggerService.TAG, "stopRecognition(): Uuid : " + parcelUuid);
            if (SoundTriggerService.this.isInitialized()) {
                return SoundTriggerService.this.mSoundTriggerHelper.stopGenericRecognition(parcelUuid.getUuid(), callback);
            }
            return Integer.MIN_VALUE;
        }

        public SoundTrigger.GenericSoundModel getSoundModel(ParcelUuid soundModelId) {
            SoundTriggerService.this.enforceCallingPermission("android.permission.MANAGE_SOUND_TRIGGER");
            Slog.i(SoundTriggerService.TAG, "getSoundModel(): id = " + soundModelId);
            SoundTrigger.GenericSoundModel model = SoundTriggerService.this.mDbHelper.getGenericSoundModel(soundModelId.getUuid());
            return model;
        }

        public void updateSoundModel(SoundTrigger.GenericSoundModel soundModel) {
            SoundTriggerService.this.enforceCallingPermission("android.permission.MANAGE_SOUND_TRIGGER");
            Slog.i(SoundTriggerService.TAG, "updateSoundModel(): model = " + soundModel);
            SoundTriggerService.this.mDbHelper.updateGenericSoundModel(soundModel);
        }

        public void deleteSoundModel(ParcelUuid soundModelId) {
            SoundTriggerService.this.enforceCallingPermission("android.permission.MANAGE_SOUND_TRIGGER");
            Slog.i(SoundTriggerService.TAG, "deleteSoundModel(): id = " + soundModelId);
            SoundTriggerService.this.mSoundTriggerHelper.unloadGenericSoundModel(soundModelId.getUuid());
            SoundTriggerService.this.mDbHelper.deleteGenericSoundModel(soundModelId.getUuid());
        }

        public int loadGenericSoundModel(SoundTrigger.GenericSoundModel soundModel) {
            SoundTriggerService.this.enforceCallingPermission("android.permission.MANAGE_SOUND_TRIGGER");
            if (SoundTriggerService.this.isInitialized()) {
                if (soundModel == null || soundModel.uuid == null) {
                    Slog.e(SoundTriggerService.TAG, "Invalid sound model");
                    return Integer.MIN_VALUE;
                }
                Slog.i(SoundTriggerService.TAG, "loadGenericSoundModel(): id = " + soundModel.uuid);
                synchronized (SoundTriggerService.this.mLock) {
                    SoundTrigger.SoundModel oldModel = (SoundTrigger.SoundModel) SoundTriggerService.this.mLoadedModels.get(soundModel.uuid);
                    if (oldModel != null && !oldModel.equals(soundModel)) {
                        SoundTriggerService.this.mSoundTriggerHelper.unloadGenericSoundModel(soundModel.uuid);
                        synchronized (SoundTriggerService.this.mCallbacksLock) {
                            SoundTriggerService.this.mCallbacks.remove(soundModel.uuid);
                        }
                    }
                    SoundTriggerService.this.mLoadedModels.put(soundModel.uuid, soundModel);
                }
                return 0;
            }
            return Integer.MIN_VALUE;
        }

        public int loadKeyphraseSoundModel(SoundTrigger.KeyphraseSoundModel soundModel) {
            SoundTriggerService.this.enforceCallingPermission("android.permission.MANAGE_SOUND_TRIGGER");
            if (SoundTriggerService.this.isInitialized()) {
                if (soundModel == null || soundModel.uuid == null) {
                    Slog.e(SoundTriggerService.TAG, "Invalid sound model");
                    return Integer.MIN_VALUE;
                } else if (soundModel.keyphrases == null || soundModel.keyphrases.length != 1) {
                    Slog.e(SoundTriggerService.TAG, "Only one keyphrase per model is currently supported.");
                    return Integer.MIN_VALUE;
                } else {
                    Slog.i(SoundTriggerService.TAG, "loadKeyphraseSoundModel(): id = " + soundModel.uuid);
                    synchronized (SoundTriggerService.this.mLock) {
                        SoundTrigger.SoundModel oldModel = (SoundTrigger.SoundModel) SoundTriggerService.this.mLoadedModels.get(soundModel.uuid);
                        if (oldModel != null && !oldModel.equals(soundModel)) {
                            SoundTriggerService.this.mSoundTriggerHelper.unloadKeyphraseSoundModel(soundModel.keyphrases[0].id);
                            synchronized (SoundTriggerService.this.mCallbacksLock) {
                                SoundTriggerService.this.mCallbacks.remove(soundModel.uuid);
                            }
                        }
                        SoundTriggerService.this.mLoadedModels.put(soundModel.uuid, soundModel);
                    }
                    return 0;
                }
            }
            return Integer.MIN_VALUE;
        }

        public int startRecognitionForService(ParcelUuid soundModelId, Bundle params, ComponentName detectionService, SoundTrigger.RecognitionConfig config) {
            Preconditions.checkNotNull(soundModelId);
            Preconditions.checkNotNull(detectionService);
            Preconditions.checkNotNull(config);
            return startRecognitionForInt(soundModelId, new RemoteSoundTriggerDetectionService(soundModelId.getUuid(), params, detectionService, Binder.getCallingUserHandle(), config), config);
        }

        public int startRecognitionForIntent(ParcelUuid soundModelId, PendingIntent callbackIntent, SoundTrigger.RecognitionConfig config) {
            return startRecognitionForInt(soundModelId, new LocalSoundTriggerRecognitionStatusIntentCallback(soundModelId.getUuid(), callbackIntent, config), config);
        }

        private int startRecognitionForInt(ParcelUuid soundModelId, IRecognitionStatusCallback callback, SoundTrigger.RecognitionConfig config) {
            IRecognitionStatusCallback existingCallback;
            int ret;
            SoundTriggerService.this.enforceCallingPermission("android.permission.MANAGE_SOUND_TRIGGER");
            if (SoundTriggerService.this.isInitialized()) {
                Slog.i(SoundTriggerService.TAG, "startRecognition(): id = " + soundModelId);
                synchronized (SoundTriggerService.this.mLock) {
                    SoundTrigger.GenericSoundModel genericSoundModel = (SoundTrigger.SoundModel) SoundTriggerService.this.mLoadedModels.get(soundModelId.getUuid());
                    if (genericSoundModel != null) {
                        synchronized (SoundTriggerService.this.mCallbacksLock) {
                            existingCallback = (IRecognitionStatusCallback) SoundTriggerService.this.mCallbacks.get(soundModelId.getUuid());
                        }
                        if (existingCallback != null) {
                            Slog.e(SoundTriggerService.TAG, soundModelId + " is already running");
                            return Integer.MIN_VALUE;
                        }
                        switch (((SoundTrigger.SoundModel) genericSoundModel).type) {
                            case 0:
                                SoundTrigger.KeyphraseSoundModel keyphraseSoundModel = (SoundTrigger.KeyphraseSoundModel) genericSoundModel;
                                ret = SoundTriggerService.this.mSoundTriggerHelper.startKeyphraseRecognition(keyphraseSoundModel.keyphrases[0].id, keyphraseSoundModel, callback, config);
                                break;
                            case 1:
                                ret = SoundTriggerService.this.mSoundTriggerHelper.startGenericRecognition(((SoundTrigger.SoundModel) genericSoundModel).uuid, genericSoundModel, callback, config);
                                break;
                            default:
                                Slog.e(SoundTriggerService.TAG, "Unknown model type");
                                return Integer.MIN_VALUE;
                        }
                        if (ret == 0) {
                            synchronized (SoundTriggerService.this.mCallbacksLock) {
                                SoundTriggerService.this.mCallbacks.put(soundModelId.getUuid(), callback);
                            }
                            return 0;
                        }
                        Slog.e(SoundTriggerService.TAG, "Failed to start model: " + ret);
                        return ret;
                    }
                    Slog.e(SoundTriggerService.TAG, soundModelId + " is not loaded");
                    return Integer.MIN_VALUE;
                }
            }
            return Integer.MIN_VALUE;
        }

        public int stopRecognitionForIntent(ParcelUuid soundModelId) {
            IRecognitionStatusCallback callback;
            int ret;
            SoundTriggerService.this.enforceCallingPermission("android.permission.MANAGE_SOUND_TRIGGER");
            if (SoundTriggerService.this.isInitialized()) {
                Slog.i(SoundTriggerService.TAG, "stopRecognition(): id = " + soundModelId);
                synchronized (SoundTriggerService.this.mLock) {
                    SoundTrigger.KeyphraseSoundModel keyphraseSoundModel = (SoundTrigger.SoundModel) SoundTriggerService.this.mLoadedModels.get(soundModelId.getUuid());
                    if (keyphraseSoundModel != null) {
                        synchronized (SoundTriggerService.this.mCallbacksLock) {
                            callback = (IRecognitionStatusCallback) SoundTriggerService.this.mCallbacks.get(soundModelId.getUuid());
                        }
                        if (callback != null) {
                            switch (((SoundTrigger.SoundModel) keyphraseSoundModel).type) {
                                case 0:
                                    ret = SoundTriggerService.this.mSoundTriggerHelper.stopKeyphraseRecognition(keyphraseSoundModel.keyphrases[0].id, callback);
                                    break;
                                case 1:
                                    ret = SoundTriggerService.this.mSoundTriggerHelper.stopGenericRecognition(((SoundTrigger.SoundModel) keyphraseSoundModel).uuid, callback);
                                    break;
                                default:
                                    Slog.e(SoundTriggerService.TAG, "Unknown model type");
                                    return Integer.MIN_VALUE;
                            }
                            if (ret == 0) {
                                synchronized (SoundTriggerService.this.mCallbacksLock) {
                                    SoundTriggerService.this.mCallbacks.remove(soundModelId.getUuid());
                                }
                                return 0;
                            }
                            Slog.e(SoundTriggerService.TAG, "Failed to stop model: " + ret);
                            return ret;
                        }
                        Slog.e(SoundTriggerService.TAG, soundModelId + " is not running");
                        return Integer.MIN_VALUE;
                    }
                    Slog.e(SoundTriggerService.TAG, soundModelId + " is not loaded");
                    return Integer.MIN_VALUE;
                }
            }
            return Integer.MIN_VALUE;
        }

        public int unloadSoundModel(ParcelUuid soundModelId) {
            int ret;
            SoundTriggerService.this.enforceCallingPermission("android.permission.MANAGE_SOUND_TRIGGER");
            if (SoundTriggerService.this.isInitialized()) {
                Slog.i(SoundTriggerService.TAG, "unloadSoundModel(): id = " + soundModelId);
                synchronized (SoundTriggerService.this.mLock) {
                    SoundTrigger.KeyphraseSoundModel keyphraseSoundModel = (SoundTrigger.SoundModel) SoundTriggerService.this.mLoadedModels.get(soundModelId.getUuid());
                    if (keyphraseSoundModel != null) {
                        switch (((SoundTrigger.SoundModel) keyphraseSoundModel).type) {
                            case 0:
                                ret = SoundTriggerService.this.mSoundTriggerHelper.unloadKeyphraseSoundModel(keyphraseSoundModel.keyphrases[0].id);
                                break;
                            case 1:
                                ret = SoundTriggerService.this.mSoundTriggerHelper.unloadGenericSoundModel(((SoundTrigger.SoundModel) keyphraseSoundModel).uuid);
                                break;
                            default:
                                Slog.e(SoundTriggerService.TAG, "Unknown model type");
                                return Integer.MIN_VALUE;
                        }
                        if (ret == 0) {
                            SoundTriggerService.this.mLoadedModels.remove(soundModelId.getUuid());
                            return 0;
                        }
                        Slog.e(SoundTriggerService.TAG, "Failed to unload model");
                        return ret;
                    }
                    Slog.e(SoundTriggerService.TAG, soundModelId + " is not loaded");
                    return Integer.MIN_VALUE;
                }
            }
            return Integer.MIN_VALUE;
        }

        public boolean isRecognitionActive(ParcelUuid parcelUuid) {
            SoundTriggerService.this.enforceCallingPermission("android.permission.MANAGE_SOUND_TRIGGER");
            if (SoundTriggerService.this.isInitialized()) {
                synchronized (SoundTriggerService.this.mCallbacksLock) {
                    IRecognitionStatusCallback callback = (IRecognitionStatusCallback) SoundTriggerService.this.mCallbacks.get(parcelUuid.getUuid());
                    if (callback == null) {
                        return false;
                    }
                    return SoundTriggerService.this.mSoundTriggerHelper.isRecognitionRequested(parcelUuid.getUuid());
                }
            }
            return false;
        }
    }

    /* loaded from: classes.dex */
    private final class LocalSoundTriggerRecognitionStatusIntentCallback extends IRecognitionStatusCallback.Stub {
        private PendingIntent mCallbackIntent;
        private SoundTrigger.RecognitionConfig mRecognitionConfig;
        private UUID mUuid;

        public LocalSoundTriggerRecognitionStatusIntentCallback(UUID modelUuid, PendingIntent callbackIntent, SoundTrigger.RecognitionConfig config) {
            this.mUuid = modelUuid;
            this.mCallbackIntent = callbackIntent;
            this.mRecognitionConfig = config;
        }

        public boolean pingBinder() {
            return this.mCallbackIntent != null;
        }

        public void onKeyphraseDetected(SoundTrigger.KeyphraseRecognitionEvent event) {
            if (this.mCallbackIntent != null) {
                SoundTriggerService.this.grabWakeLock();
                Slog.w(SoundTriggerService.TAG, "Keyphrase sound trigger event: " + event);
                Intent extras = new Intent();
                extras.putExtra("android.media.soundtrigger.MESSAGE_TYPE", 0);
                extras.putExtra("android.media.soundtrigger.RECOGNITION_EVENT", (Parcelable) event);
                try {
                    this.mCallbackIntent.send(SoundTriggerService.this.mContext, 0, extras, SoundTriggerService.this.mCallbackCompletedHandler, null);
                    if (!this.mRecognitionConfig.allowMultipleTriggers) {
                        removeCallback(false);
                    }
                } catch (PendingIntent.CanceledException e) {
                    removeCallback(true);
                }
            }
        }

        public void onGenericSoundTriggerDetected(SoundTrigger.GenericRecognitionEvent event) {
            if (this.mCallbackIntent != null) {
                SoundTriggerService.this.grabWakeLock();
                Slog.w(SoundTriggerService.TAG, "Generic sound trigger event: " + event);
                Intent extras = new Intent();
                extras.putExtra("android.media.soundtrigger.MESSAGE_TYPE", 0);
                extras.putExtra("android.media.soundtrigger.RECOGNITION_EVENT", (Parcelable) event);
                try {
                    this.mCallbackIntent.send(SoundTriggerService.this.mContext, 0, extras, SoundTriggerService.this.mCallbackCompletedHandler, null);
                    if (!this.mRecognitionConfig.allowMultipleTriggers) {
                        removeCallback(false);
                    }
                } catch (PendingIntent.CanceledException e) {
                    removeCallback(true);
                }
            }
        }

        public void onError(int status) {
            if (this.mCallbackIntent != null) {
                SoundTriggerService.this.grabWakeLock();
                Slog.i(SoundTriggerService.TAG, "onError: " + status);
                Intent extras = new Intent();
                extras.putExtra("android.media.soundtrigger.MESSAGE_TYPE", 1);
                extras.putExtra("android.media.soundtrigger.STATUS", status);
                try {
                    this.mCallbackIntent.send(SoundTriggerService.this.mContext, 0, extras, SoundTriggerService.this.mCallbackCompletedHandler, null);
                    removeCallback(false);
                } catch (PendingIntent.CanceledException e) {
                    removeCallback(true);
                }
            }
        }

        public void onRecognitionPaused() {
            if (this.mCallbackIntent != null) {
                SoundTriggerService.this.grabWakeLock();
                Slog.i(SoundTriggerService.TAG, "onRecognitionPaused");
                Intent extras = new Intent();
                extras.putExtra("android.media.soundtrigger.MESSAGE_TYPE", 2);
                try {
                    this.mCallbackIntent.send(SoundTriggerService.this.mContext, 0, extras, SoundTriggerService.this.mCallbackCompletedHandler, null);
                } catch (PendingIntent.CanceledException e) {
                    removeCallback(true);
                }
            }
        }

        public void onRecognitionResumed() {
            if (this.mCallbackIntent != null) {
                SoundTriggerService.this.grabWakeLock();
                Slog.i(SoundTriggerService.TAG, "onRecognitionResumed");
                Intent extras = new Intent();
                extras.putExtra("android.media.soundtrigger.MESSAGE_TYPE", 3);
                try {
                    this.mCallbackIntent.send(SoundTriggerService.this.mContext, 0, extras, SoundTriggerService.this.mCallbackCompletedHandler, null);
                } catch (PendingIntent.CanceledException e) {
                    removeCallback(true);
                }
            }
        }

        private void removeCallback(boolean releaseWakeLock) {
            this.mCallbackIntent = null;
            synchronized (SoundTriggerService.this.mCallbacksLock) {
                SoundTriggerService.this.mCallbacks.remove(this.mUuid);
                if (releaseWakeLock) {
                    SoundTriggerService.this.mWakelock.release();
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class NumOps {
        @GuardedBy("mLock")
        private long mLastOpsHourSinceBoot;
        private final Object mLock;
        @GuardedBy("mLock")
        private int[] mNumOps;

        private NumOps() {
            this.mLock = new Object();
            this.mNumOps = new int[24];
        }

        void clearOldOps(long currentTime) {
            synchronized (this.mLock) {
                long numHoursSinceBoot = TimeUnit.HOURS.convert(currentTime, TimeUnit.NANOSECONDS);
                if (this.mLastOpsHourSinceBoot != 0) {
                    long hour = this.mLastOpsHourSinceBoot;
                    while (true) {
                        hour++;
                        if (hour > numHoursSinceBoot) {
                            break;
                        }
                        this.mNumOps[(int) (hour % 24)] = 0;
                    }
                }
            }
        }

        void addOp(long currentTime) {
            synchronized (this.mLock) {
                long numHoursSinceBoot = TimeUnit.HOURS.convert(currentTime, TimeUnit.NANOSECONDS);
                int[] iArr = this.mNumOps;
                int i = (int) (numHoursSinceBoot % 24);
                iArr[i] = iArr[i] + 1;
                this.mLastOpsHourSinceBoot = numHoursSinceBoot;
            }
        }

        int getOpsAdded() {
            int totalOperationsInLastDay;
            synchronized (this.mLock) {
                totalOperationsInLastDay = 0;
                for (int i = 0; i < 24; i++) {
                    try {
                        totalOperationsInLastDay += this.mNumOps[i];
                    } catch (Throwable th) {
                        throw th;
                    }
                }
            }
            return totalOperationsInLastDay;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class Operation {
        private final Runnable mDropOp;
        private final ExecuteOp mExecuteOp;
        private final Runnable mSetupOp;

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes.dex */
        public interface ExecuteOp {
            void run(int i, ISoundTriggerDetectionService iSoundTriggerDetectionService) throws RemoteException;
        }

        private Operation(Runnable setupOp, ExecuteOp executeOp, Runnable cancelOp) {
            this.mSetupOp = setupOp;
            this.mExecuteOp = executeOp;
            this.mDropOp = cancelOp;
        }

        private void setup() {
            if (this.mSetupOp != null) {
                this.mSetupOp.run();
            }
        }

        void run(int opId, ISoundTriggerDetectionService service) throws RemoteException {
            setup();
            this.mExecuteOp.run(opId, service);
        }

        void drop() {
            setup();
            if (this.mDropOp != null) {
                this.mDropOp.run();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class RemoteSoundTriggerDetectionService extends IRecognitionStatusCallback.Stub implements ServiceConnection {
        private static final int MSG_STOP_ALL_PENDING_OPERATIONS = 1;
        private final ISoundTriggerDetectionServiceClient mClient;
        @GuardedBy("mRemoteServiceLock")
        private boolean mDestroyOnceRunningOpsDone;
        @GuardedBy("mRemoteServiceLock")
        private boolean mIsBound;
        @GuardedBy("mRemoteServiceLock")
        private boolean mIsDestroyed;
        private final NumOps mNumOps;
        @GuardedBy("mRemoteServiceLock")
        private int mNumTotalOpsPerformed;
        private final Bundle mParams;
        private final ParcelUuid mPuuid;
        private final SoundTrigger.RecognitionConfig mRecognitionConfig;
        private final PowerManager.WakeLock mRemoteServiceWakeLock;
        @GuardedBy("mRemoteServiceLock")
        private ISoundTriggerDetectionService mService;
        private final ComponentName mServiceName;
        private final UserHandle mUser;
        private final Object mRemoteServiceLock = new Object();
        @GuardedBy("mRemoteServiceLock")
        private final ArrayList<Operation> mPendingOps = new ArrayList<>();
        @GuardedBy("mRemoteServiceLock")
        private final ArraySet<Integer> mRunningOpIds = new ArraySet<>();
        private final Handler mHandler = new Handler(Looper.getMainLooper());

        public RemoteSoundTriggerDetectionService(UUID modelUuid, Bundle params, ComponentName serviceName, UserHandle user, SoundTrigger.RecognitionConfig config) {
            this.mPuuid = new ParcelUuid(modelUuid);
            this.mParams = params;
            this.mServiceName = serviceName;
            this.mUser = user;
            this.mRecognitionConfig = config;
            PowerManager pm = (PowerManager) SoundTriggerService.this.mContext.getSystemService("power");
            this.mRemoteServiceWakeLock = pm.newWakeLock(1, "RemoteSoundTriggerDetectionService " + this.mServiceName.getPackageName() + ":" + this.mServiceName.getClassName());
            synchronized (SoundTriggerService.this.mLock) {
                NumOps numOps = (NumOps) SoundTriggerService.this.mNumOpsPerPackage.get(this.mServiceName.getPackageName());
                if (numOps == null) {
                    numOps = new NumOps();
                    SoundTriggerService.this.mNumOpsPerPackage.put(this.mServiceName.getPackageName(), numOps);
                }
                this.mNumOps = numOps;
            }
            this.mClient = new ISoundTriggerDetectionServiceClient.Stub() { // from class: com.android.server.soundtrigger.SoundTriggerService.RemoteSoundTriggerDetectionService.1
                public void onOpFinished(int opId) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        synchronized (RemoteSoundTriggerDetectionService.this.mRemoteServiceLock) {
                            RemoteSoundTriggerDetectionService.this.mRunningOpIds.remove(Integer.valueOf(opId));
                            if (RemoteSoundTriggerDetectionService.this.mRunningOpIds.isEmpty() && RemoteSoundTriggerDetectionService.this.mPendingOps.isEmpty()) {
                                if (RemoteSoundTriggerDetectionService.this.mDestroyOnceRunningOpsDone) {
                                    RemoteSoundTriggerDetectionService.this.destroy();
                                } else {
                                    RemoteSoundTriggerDetectionService.this.disconnectLocked();
                                }
                            }
                        }
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            };
        }

        public boolean pingBinder() {
            return (this.mIsDestroyed || this.mDestroyOnceRunningOpsDone) ? false : true;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void disconnectLocked() {
            if (this.mService != null) {
                try {
                    this.mService.removeClient(this.mPuuid);
                } catch (Exception e) {
                    Slog.e(SoundTriggerService.TAG, this.mPuuid + ": Cannot remove client", e);
                }
                this.mService = null;
            }
            if (this.mIsBound) {
                SoundTriggerService.this.mContext.unbindService(this);
                this.mIsBound = false;
                synchronized (SoundTriggerService.this.mCallbacksLock) {
                    this.mRemoteServiceWakeLock.release();
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void destroy() {
            Slog.v(SoundTriggerService.TAG, this.mPuuid + ": destroy");
            synchronized (this.mRemoteServiceLock) {
                disconnectLocked();
                this.mIsDestroyed = true;
            }
            if (!this.mDestroyOnceRunningOpsDone) {
                synchronized (SoundTriggerService.this.mCallbacksLock) {
                    SoundTriggerService.this.mCallbacks.remove(this.mPuuid.getUuid());
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void stopAllPendingOperations() {
            synchronized (this.mRemoteServiceLock) {
                if (this.mIsDestroyed) {
                    return;
                }
                if (this.mService != null) {
                    int numOps = this.mRunningOpIds.size();
                    for (int i = 0; i < numOps; i++) {
                        try {
                            this.mService.onStopOperation(this.mPuuid, this.mRunningOpIds.valueAt(i).intValue());
                        } catch (Exception e) {
                            Slog.e(SoundTriggerService.TAG, this.mPuuid + ": Could not stop operation " + this.mRunningOpIds.valueAt(i), e);
                        }
                    }
                    this.mRunningOpIds.clear();
                }
                disconnectLocked();
            }
        }

        private void bind() {
            long token = Binder.clearCallingIdentity();
            try {
                Intent i = new Intent();
                i.setComponent(this.mServiceName);
                ResolveInfo ri = SoundTriggerService.this.mContext.getPackageManager().resolveServiceAsUser(i, 268435588, this.mUser.getIdentifier());
                if (ri == null) {
                    Slog.w(SoundTriggerService.TAG, this.mPuuid + ": " + this.mServiceName + " not found");
                } else if (!"android.permission.BIND_SOUND_TRIGGER_DETECTION_SERVICE".equals(ri.serviceInfo.permission)) {
                    Slog.w(SoundTriggerService.TAG, this.mPuuid + ": " + this.mServiceName + " does not require android.permission.BIND_SOUND_TRIGGER_DETECTION_SERVICE");
                } else {
                    this.mIsBound = SoundTriggerService.this.mContext.bindServiceAsUser(i, this, 67108865, this.mUser);
                    if (this.mIsBound) {
                        this.mRemoteServiceWakeLock.acquire();
                    } else {
                        Slog.w(SoundTriggerService.TAG, this.mPuuid + ": Could not bind to " + this.mServiceName);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private void runOrAddOperation(Operation op) {
            synchronized (this.mRemoteServiceLock) {
                if (!this.mIsDestroyed && !this.mDestroyOnceRunningOpsDone) {
                    if (this.mService == null) {
                        this.mPendingOps.add(op);
                        if (!this.mIsBound) {
                            bind();
                        }
                    } else {
                        long currentTime = System.nanoTime();
                        this.mNumOps.clearOldOps(currentTime);
                        Settings.Global.getInt(SoundTriggerService.this.mContext.getContentResolver(), "max_sound_trigger_detection_service_ops_per_day", Integer.MAX_VALUE);
                        this.mNumOps.getOpsAdded();
                        this.mNumOps.addOp(currentTime);
                        int opId = this.mNumTotalOpsPerformed;
                        do {
                            this.mNumTotalOpsPerformed++;
                        } while (this.mRunningOpIds.contains(Integer.valueOf(opId)));
                        try {
                            Slog.v(SoundTriggerService.TAG, this.mPuuid + ": runOp " + opId);
                            op.run(opId, this.mService);
                            this.mRunningOpIds.add(Integer.valueOf(opId));
                        } catch (Exception e) {
                            Slog.e(SoundTriggerService.TAG, this.mPuuid + ": Could not run operation " + opId, e);
                        }
                        if (this.mPendingOps.isEmpty() && this.mRunningOpIds.isEmpty()) {
                            if (this.mDestroyOnceRunningOpsDone) {
                                destroy();
                            } else {
                                disconnectLocked();
                            }
                        } else {
                            this.mHandler.removeMessages(1);
                            this.mHandler.sendMessageDelayed(PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.soundtrigger.-$$Lambda$SoundTriggerService$RemoteSoundTriggerDetectionService$wfDlqQ7aPvu9qZCZ24jJu4tfUMY
                                @Override // java.util.function.Consumer
                                public final void accept(Object obj) {
                                    ((SoundTriggerService.RemoteSoundTriggerDetectionService) obj).stopAllPendingOperations();
                                }
                            }, this).setWhat(1), Settings.Global.getLong(SoundTriggerService.this.mContext.getContentResolver(), "sound_trigger_detection_service_op_timeout", JobStatus.NO_LATEST_RUNTIME));
                        }
                    }
                    return;
                }
                Slog.w(SoundTriggerService.TAG, this.mPuuid + ": Dropped operation as already destroyed or marked for destruction");
                op.drop();
            }
        }

        public void onKeyphraseDetected(SoundTrigger.KeyphraseRecognitionEvent event) {
            Slog.w(SoundTriggerService.TAG, this.mPuuid + "->" + this.mServiceName + ": IGNORED onKeyphraseDetected(" + event + ")");
        }

        private AudioRecord createAudioRecordForEvent(SoundTrigger.GenericRecognitionEvent event) {
            int sampleRate;
            int i;
            AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder();
            attributesBuilder.setInternalCapturePreset(IdentifierType.VENDOR_END);
            AudioAttributes attributes = attributesBuilder.build();
            AudioFormat originalFormat = event.getCaptureFormat();
            AudioFormat captureFormat = new AudioFormat.Builder().setChannelMask(originalFormat.getChannelMask()).setEncoding(originalFormat.getEncoding()).setSampleRate(originalFormat.getSampleRate()).build();
            if (captureFormat.getSampleRate() == 0) {
                sampleRate = 192000;
            } else {
                sampleRate = captureFormat.getSampleRate();
            }
            if (captureFormat.getChannelCount() == 2) {
                i = 12;
            } else {
                i = 16;
            }
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate, i, captureFormat.getEncoding());
            return new AudioRecord(attributes, captureFormat, bufferSize, event.getCaptureSession());
        }

        public void onGenericSoundTriggerDetected(final SoundTrigger.GenericRecognitionEvent event) {
            Slog.v(SoundTriggerService.TAG, this.mPuuid + ": Generic sound trigger event: " + event);
            runOrAddOperation(new Operation(new Runnable() { // from class: com.android.server.soundtrigger.-$$Lambda$SoundTriggerService$RemoteSoundTriggerDetectionService$yqLMvkOmrO13yWrggtSaVrLgsWo
                @Override // java.lang.Runnable
                public final void run() {
                    SoundTriggerService.RemoteSoundTriggerDetectionService.lambda$onGenericSoundTriggerDetected$0(SoundTriggerService.RemoteSoundTriggerDetectionService.this);
                }
            }, new Operation.ExecuteOp() { // from class: com.android.server.soundtrigger.-$$Lambda$SoundTriggerService$RemoteSoundTriggerDetectionService$F-iA254xzDfAHrQW86c2oSqXfwI
                @Override // com.android.server.soundtrigger.SoundTriggerService.Operation.ExecuteOp
                public final void run(int i, ISoundTriggerDetectionService iSoundTriggerDetectionService) {
                    iSoundTriggerDetectionService.onGenericRecognitionEvent(SoundTriggerService.RemoteSoundTriggerDetectionService.this.mPuuid, i, event);
                }
            }, new Runnable() { // from class: com.android.server.soundtrigger.-$$Lambda$SoundTriggerService$RemoteSoundTriggerDetectionService$pFqiq_C9KJsoa_HQOdj7lmMixsI
                @Override // java.lang.Runnable
                public final void run() {
                    SoundTriggerService.RemoteSoundTriggerDetectionService.lambda$onGenericSoundTriggerDetected$2(SoundTriggerService.RemoteSoundTriggerDetectionService.this, event);
                }
            }));
        }

        public static /* synthetic */ void lambda$onGenericSoundTriggerDetected$0(RemoteSoundTriggerDetectionService remoteSoundTriggerDetectionService) {
            if (!remoteSoundTriggerDetectionService.mRecognitionConfig.allowMultipleTriggers) {
                synchronized (SoundTriggerService.this.mCallbacksLock) {
                    SoundTriggerService.this.mCallbacks.remove(remoteSoundTriggerDetectionService.mPuuid.getUuid());
                }
                remoteSoundTriggerDetectionService.mDestroyOnceRunningOpsDone = true;
            }
        }

        public static /* synthetic */ void lambda$onGenericSoundTriggerDetected$2(RemoteSoundTriggerDetectionService remoteSoundTriggerDetectionService, SoundTrigger.GenericRecognitionEvent event) {
            if (event.isCaptureAvailable()) {
                AudioRecord capturedData = remoteSoundTriggerDetectionService.createAudioRecordForEvent(event);
                capturedData.startRecording();
                capturedData.release();
            }
        }

        public void onError(final int status) {
            Slog.v(SoundTriggerService.TAG, this.mPuuid + ": onError: " + status);
            runOrAddOperation(new Operation(new Runnable() { // from class: com.android.server.soundtrigger.-$$Lambda$SoundTriggerService$RemoteSoundTriggerDetectionService$t5mBYXswwLAAdm47WS10stLjYng
                @Override // java.lang.Runnable
                public final void run() {
                    SoundTriggerService.RemoteSoundTriggerDetectionService.lambda$onError$3(SoundTriggerService.RemoteSoundTriggerDetectionService.this);
                }
            }, new Operation.ExecuteOp() { // from class: com.android.server.soundtrigger.-$$Lambda$SoundTriggerService$RemoteSoundTriggerDetectionService$crQZgbDmIG6q92Mrkm49T2yqrs0
                @Override // com.android.server.soundtrigger.SoundTriggerService.Operation.ExecuteOp
                public final void run(int i, ISoundTriggerDetectionService iSoundTriggerDetectionService) {
                    iSoundTriggerDetectionService.onError(SoundTriggerService.RemoteSoundTriggerDetectionService.this.mPuuid, i, status);
                }
            }, null));
        }

        public static /* synthetic */ void lambda$onError$3(RemoteSoundTriggerDetectionService remoteSoundTriggerDetectionService) {
            synchronized (SoundTriggerService.this.mCallbacksLock) {
                SoundTriggerService.this.mCallbacks.remove(remoteSoundTriggerDetectionService.mPuuid.getUuid());
            }
            remoteSoundTriggerDetectionService.mDestroyOnceRunningOpsDone = true;
        }

        public void onRecognitionPaused() {
            Slog.i(SoundTriggerService.TAG, this.mPuuid + "->" + this.mServiceName + ": IGNORED onRecognitionPaused");
        }

        public void onRecognitionResumed() {
            Slog.i(SoundTriggerService.TAG, this.mPuuid + "->" + this.mServiceName + ": IGNORED onRecognitionResumed");
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            Slog.v(SoundTriggerService.TAG, this.mPuuid + ": onServiceConnected(" + service + ")");
            synchronized (this.mRemoteServiceLock) {
                this.mService = ISoundTriggerDetectionService.Stub.asInterface(service);
                try {
                    this.mService.setClient(this.mPuuid, this.mParams, this.mClient);
                    while (!this.mPendingOps.isEmpty()) {
                        runOrAddOperation(this.mPendingOps.remove(0));
                    }
                } catch (Exception e) {
                    Slog.e(SoundTriggerService.TAG, this.mPuuid + ": Could not init " + this.mServiceName, e);
                }
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            Slog.v(SoundTriggerService.TAG, this.mPuuid + ": onServiceDisconnected");
            synchronized (this.mRemoteServiceLock) {
                this.mService = null;
            }
        }

        @Override // android.content.ServiceConnection
        public void onBindingDied(ComponentName name) {
            Slog.v(SoundTriggerService.TAG, this.mPuuid + ": onBindingDied");
            synchronized (this.mRemoteServiceLock) {
                destroy();
            }
        }

        @Override // android.content.ServiceConnection
        public void onNullBinding(ComponentName name) {
            Slog.w(SoundTriggerService.TAG, name + " for model " + this.mPuuid + " returned a null binding");
            synchronized (this.mRemoteServiceLock) {
                disconnectLocked();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void grabWakeLock() {
        synchronized (this.mCallbacksLock) {
            if (this.mWakelock == null) {
                PowerManager pm = (PowerManager) this.mContext.getSystemService("power");
                this.mWakelock = pm.newWakeLock(1, TAG);
            }
            this.mWakelock.acquire();
        }
    }

    /* loaded from: classes.dex */
    public final class LocalSoundTriggerService extends SoundTriggerInternal {
        private final Context mContext;
        private SoundTriggerHelper mSoundTriggerHelper;

        LocalSoundTriggerService(Context context) {
            this.mContext = context;
        }

        synchronized void setSoundTriggerHelper(SoundTriggerHelper helper) {
            this.mSoundTriggerHelper = helper;
        }

        @Override // com.android.server.soundtrigger.SoundTriggerInternal
        public int startRecognition(int keyphraseId, SoundTrigger.KeyphraseSoundModel soundModel, IRecognitionStatusCallback listener, SoundTrigger.RecognitionConfig recognitionConfig) {
            if (isInitialized()) {
                return this.mSoundTriggerHelper.startKeyphraseRecognition(keyphraseId, soundModel, listener, recognitionConfig);
            }
            return Integer.MIN_VALUE;
        }

        @Override // com.android.server.soundtrigger.SoundTriggerInternal
        public synchronized int stopRecognition(int keyphraseId, IRecognitionStatusCallback listener) {
            if (isInitialized()) {
                return this.mSoundTriggerHelper.stopKeyphraseRecognition(keyphraseId, listener);
            }
            return Integer.MIN_VALUE;
        }

        @Override // com.android.server.soundtrigger.SoundTriggerInternal
        public SoundTrigger.ModuleProperties getModuleProperties() {
            if (isInitialized()) {
                return this.mSoundTriggerHelper.getModuleProperties();
            }
            return null;
        }

        @Override // com.android.server.soundtrigger.SoundTriggerInternal
        public int unloadKeyphraseModel(int keyphraseId) {
            if (isInitialized()) {
                return this.mSoundTriggerHelper.unloadKeyphraseSoundModel(keyphraseId);
            }
            return Integer.MIN_VALUE;
        }

        @Override // com.android.server.soundtrigger.SoundTriggerInternal
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (isInitialized()) {
                this.mSoundTriggerHelper.dump(fd, pw, args);
            }
        }

        private synchronized boolean isInitialized() {
            if (this.mSoundTriggerHelper == null) {
                Slog.e(SoundTriggerService.TAG, "SoundTriggerHelper not initialized.");
                return false;
            }
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enforceCallingPermission(String permission) {
        if (this.mContext.checkCallingOrSelfPermission(permission) != 0) {
            throw new SecurityException("Caller does not hold the permission " + permission);
        }
    }
}
