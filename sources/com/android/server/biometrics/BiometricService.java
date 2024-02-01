package com.android.server.biometrics;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AppOpsManager;
import android.app.IActivityTaskManager;
import android.app.KeyguardManager;
import android.app.TaskStackListener;
import android.app.UserSwitchObserver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.IBiometricConfirmDeviceCredentialCallback;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.hardware.face.FaceManager;
import android.hardware.face.IFaceService;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintService;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.KeyStore;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.StatsLog;
import com.android.internal.os.SomeArgs;
import com.android.internal.statusbar.IStatusBarService;
import com.android.server.SystemService;
import com.android.server.biometrics.BiometricService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/* loaded from: classes.dex */
public class BiometricService extends SystemService {
    private static final boolean DEBUG = true;
    private static final int[] FEATURE_ID = {1, 2, 4};
    private static final int MSG_AUTHENTICATE = 9;
    private static final int MSG_CANCEL_AUTHENTICATION = 10;
    private static final int MSG_ON_ACQUIRED = 5;
    private static final int MSG_ON_AUTHENTICATION_FAILED = 3;
    private static final int MSG_ON_AUTHENTICATION_SUCCEEDED = 2;
    private static final int MSG_ON_CONFIRM_DEVICE_CREDENTIAL_ERROR = 12;
    private static final int MSG_ON_CONFIRM_DEVICE_CREDENTIAL_SUCCESS = 11;
    private static final int MSG_ON_DISMISSED = 6;
    private static final int MSG_ON_ERROR = 4;
    private static final int MSG_ON_READY_FOR_AUTHENTICATION = 8;
    private static final int MSG_ON_TASK_STACK_CHANGED = 1;
    private static final int MSG_ON_TRY_AGAIN_PRESSED = 7;
    private static final int MSG_REGISTER_CANCELLATION_CALLBACK = 13;
    private static final int STATE_AUTH_CALLED = 1;
    private static final int STATE_AUTH_IDLE = 0;
    private static final int STATE_AUTH_PAUSED = 3;
    private static final int STATE_AUTH_PENDING_CONFIRM = 5;
    private static final int STATE_AUTH_STARTED = 2;
    private static final int STATE_BIOMETRIC_AUTH_CANCELED_SHOWING_CDC = 6;
    private static final String TAG = "BiometricService";
    private IActivityTaskManager mActivityTaskManager;
    private final AppOpsManager mAppOps;
    final ArrayList<Authenticator> mAuthenticators;
    private IBiometricServiceReceiver mConfirmDeviceCredentialReceiver;
    private AuthSession mCurrentAuthSession;
    private int mCurrentModality;
    private final List<EnabledOnKeyguardCallback> mEnabledOnKeyguardCallbacks;
    private IFaceService mFaceService;
    private IFingerprintService mFingerprintService;
    private final Handler mHandler;
    private final boolean mHasFeatureFace;
    private final boolean mHasFeatureFingerprint;
    private final boolean mHasFeatureIris;
    private final IBiometricServiceReceiverInternal mInternalReceiver;
    private AuthSession mPendingAuthSession;
    private final Random mRandom;
    private final SettingObserver mSettingObserver;
    private IStatusBarService mStatusBarService;
    private final BiometricTaskStackListener mTaskStackListener;

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class AuthSession implements IBinder.DeathRecipient {
        private long mAuthenticatedTimeMs;
        final Bundle mBundle;
        final int mCallingPid;
        final int mCallingUid;
        final int mCallingUserId;
        final IBiometricServiceReceiver mClientReceiver;
        private IBiometricConfirmDeviceCredentialCallback mConfirmDeviceCredentialCallback;
        final HashMap<Integer, Integer> mModalitiesWaiting;
        final int mModality;
        final String mOpPackageName;
        final boolean mRequireConfirmation;
        final long mSessionId;
        private long mStartTimeMs;
        final IBinder mToken;
        byte[] mTokenEscrow;
        final int mUserId;
        final HashMap<Integer, Integer> mModalitiesMatched = new HashMap<>();
        private int mState = 0;

        AuthSession(HashMap<Integer, Integer> modalities, IBinder token, long sessionId, int userId, IBiometricServiceReceiver receiver, String opPackageName, Bundle bundle, int callingUid, int callingPid, int callingUserId, int modality, boolean requireConfirmation, IBiometricConfirmDeviceCredentialCallback callback) {
            this.mModalitiesWaiting = modalities;
            this.mToken = token;
            this.mSessionId = sessionId;
            this.mUserId = userId;
            this.mClientReceiver = receiver;
            this.mOpPackageName = opPackageName;
            this.mBundle = bundle;
            this.mCallingUid = callingUid;
            this.mCallingPid = callingPid;
            this.mCallingUserId = callingUserId;
            this.mModality = modality;
            this.mRequireConfirmation = requireConfirmation;
            this.mConfirmDeviceCredentialCallback = callback;
            if (isFromConfirmDeviceCredential()) {
                try {
                    token.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    Slog.e(BiometricService.TAG, "Unable to link to death", e);
                }
            }
        }

        boolean isCrypto() {
            return this.mSessionId != 0;
        }

        boolean isFromConfirmDeviceCredential() {
            return this.mBundle.getBoolean("from_confirm_device_credential", false);
        }

        boolean containsCookie(int cookie) {
            HashMap<Integer, Integer> hashMap = this.mModalitiesWaiting;
            if (hashMap == null || !hashMap.containsValue(Integer.valueOf(cookie))) {
                HashMap<Integer, Integer> hashMap2 = this.mModalitiesMatched;
                return hashMap2 != null && hashMap2.containsValue(Integer.valueOf(cookie));
            }
            return true;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            BiometricService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.-$$Lambda$BiometricService$AuthSession$pTLzev-9zTLzcrAMmVYjbC4Dbjc
                @Override // java.lang.Runnable
                public final void run() {
                    BiometricService.AuthSession.this.lambda$binderDied$0$BiometricService$AuthSession();
                }
            });
        }

        public /* synthetic */ void lambda$binderDied$0$BiometricService$AuthSession() {
            Slog.e(BiometricService.TAG, "Binder died, killing ConfirmDeviceCredential");
            IBiometricConfirmDeviceCredentialCallback iBiometricConfirmDeviceCredentialCallback = this.mConfirmDeviceCredentialCallback;
            if (iBiometricConfirmDeviceCredentialCallback == null) {
                Slog.e(BiometricService.TAG, "Callback is null");
                return;
            }
            try {
                iBiometricConfirmDeviceCredentialCallback.cancel();
                this.mConfirmDeviceCredentialCallback = null;
            } catch (RemoteException e) {
                Slog.e(BiometricService.TAG, "Unable to send cancel", e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class BiometricTaskStackListener extends TaskStackListener {
        private BiometricTaskStackListener() {
        }

        public void onTaskStackChanged() {
            BiometricService.this.mHandler.sendEmptyMessage(1);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class Authenticator {
        BiometricAuthenticator mAuthenticator;
        int mType;

        Authenticator(int type, BiometricAuthenticator authenticator) {
            this.mType = type;
            this.mAuthenticator = authenticator;
        }

        int getType() {
            return this.mType;
        }

        BiometricAuthenticator getAuthenticator() {
            return this.mAuthenticator;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class SettingObserver extends ContentObserver {
        private static final boolean DEFAULT_ALWAYS_REQUIRE_CONFIRMATION = false;
        private static final boolean DEFAULT_APP_ENABLED = true;
        private static final boolean DEFAULT_KEYGUARD_ENABLED = true;
        private final Uri FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION;
        private final Uri FACE_UNLOCK_APP_ENABLED;
        private final Uri FACE_UNLOCK_KEYGUARD_ENABLED;
        private final ContentResolver mContentResolver;
        private Map<Integer, Boolean> mFaceAlwaysRequireConfirmation;
        private Map<Integer, Boolean> mFaceEnabledForApps;
        private Map<Integer, Boolean> mFaceEnabledOnKeyguard;

        SettingObserver(Handler handler) {
            super(handler);
            this.FACE_UNLOCK_KEYGUARD_ENABLED = Settings.Secure.getUriFor("face_unlock_keyguard_enabled");
            this.FACE_UNLOCK_APP_ENABLED = Settings.Secure.getUriFor("face_unlock_app_enabled");
            this.FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION = Settings.Secure.getUriFor("face_unlock_always_require_confirmation");
            this.mFaceEnabledOnKeyguard = new HashMap();
            this.mFaceEnabledForApps = new HashMap();
            this.mFaceAlwaysRequireConfirmation = new HashMap();
            this.mContentResolver = BiometricService.this.getContext().getContentResolver();
            updateContentObserver();
        }

        void updateContentObserver() {
            this.mContentResolver.unregisterContentObserver(this);
            this.mContentResolver.registerContentObserver(this.FACE_UNLOCK_KEYGUARD_ENABLED, false, this, -1);
            this.mContentResolver.registerContentObserver(this.FACE_UNLOCK_APP_ENABLED, false, this, -1);
            this.mContentResolver.registerContentObserver(this.FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION, false, this, -1);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (this.FACE_UNLOCK_KEYGUARD_ENABLED.equals(uri)) {
                this.mFaceEnabledOnKeyguard.put(Integer.valueOf(userId), Boolean.valueOf(Settings.Secure.getIntForUser(this.mContentResolver, "face_unlock_keyguard_enabled", 1, userId) != 0));
                if (userId == ActivityManager.getCurrentUser() && !selfChange) {
                    notifyEnabledOnKeyguardCallbacks(userId);
                }
            } else if (this.FACE_UNLOCK_APP_ENABLED.equals(uri)) {
                this.mFaceEnabledForApps.put(Integer.valueOf(userId), Boolean.valueOf(Settings.Secure.getIntForUser(this.mContentResolver, "face_unlock_app_enabled", 1, userId) != 0));
            } else if (this.FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION.equals(uri)) {
                this.mFaceAlwaysRequireConfirmation.put(Integer.valueOf(userId), Boolean.valueOf(Settings.Secure.getIntForUser(this.mContentResolver, "face_unlock_always_require_confirmation", 0, userId) != 0));
            }
        }

        boolean getFaceEnabledOnKeyguard() {
            int user = ActivityManager.getCurrentUser();
            if (!this.mFaceEnabledOnKeyguard.containsKey(Integer.valueOf(user))) {
                onChange(true, this.FACE_UNLOCK_KEYGUARD_ENABLED, user);
            }
            return this.mFaceEnabledOnKeyguard.get(Integer.valueOf(user)).booleanValue();
        }

        boolean getFaceEnabledForApps(int userId) {
            if (!this.mFaceEnabledForApps.containsKey(Integer.valueOf(userId))) {
                onChange(true, this.FACE_UNLOCK_APP_ENABLED, userId);
            }
            return this.mFaceEnabledForApps.getOrDefault(Integer.valueOf(userId), true).booleanValue();
        }

        boolean getFaceAlwaysRequireConfirmation(int userId) {
            if (!this.mFaceAlwaysRequireConfirmation.containsKey(Integer.valueOf(userId))) {
                onChange(true, this.FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION, userId);
            }
            return this.mFaceAlwaysRequireConfirmation.get(Integer.valueOf(userId)).booleanValue();
        }

        void notifyEnabledOnKeyguardCallbacks(int userId) {
            List<EnabledOnKeyguardCallback> callbacks = BiometricService.this.mEnabledOnKeyguardCallbacks;
            for (int i = 0; i < callbacks.size(); i++) {
                callbacks.get(i).notify(BiometricSourceType.FACE, this.mFaceEnabledOnKeyguard.getOrDefault(Integer.valueOf(userId), true).booleanValue(), userId);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class EnabledOnKeyguardCallback implements IBinder.DeathRecipient {
        private final IBiometricEnabledOnKeyguardCallback mCallback;

        EnabledOnKeyguardCallback(IBiometricEnabledOnKeyguardCallback callback) {
            this.mCallback = callback;
            try {
                this.mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                Slog.w(BiometricService.TAG, "Unable to linkToDeath", e);
            }
        }

        void notify(BiometricSourceType sourceType, boolean enabled, int userId) {
            try {
                this.mCallback.onChanged(sourceType, enabled, userId);
            } catch (DeadObjectException e) {
                Slog.w(BiometricService.TAG, "Death while invoking notify", e);
                BiometricService.this.mEnabledOnKeyguardCallbacks.remove(this);
            } catch (RemoteException e2) {
                Slog.w(BiometricService.TAG, "Failed to invoke onChanged", e2);
            }
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            Slog.e(BiometricService.TAG, "Enabled callback binder died");
            BiometricService.this.mEnabledOnKeyguardCallbacks.remove(this);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class BiometricServiceWrapper extends IBiometricService.Stub {
        private BiometricServiceWrapper() {
        }

        public void onReadyForAuthentication(int cookie, boolean requireConfirmation, int userId) {
            BiometricService.this.checkInternalPermission();
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = cookie;
            args.arg1 = Boolean.valueOf(requireConfirmation);
            args.argi2 = userId;
            BiometricService.this.mHandler.obtainMessage(8, args).sendToTarget();
        }

        public void authenticate(IBinder token, long sessionId, final int userId, final IBiometricServiceReceiver receiver, String opPackageName, final Bundle bundle, IBiometricConfirmDeviceCredentialCallback callback) throws RemoteException {
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            int callingUserId = UserHandle.getCallingUserId();
            if (callback != null) {
                BiometricService.this.checkInternalPermission();
            }
            if (userId == callingUserId) {
                BiometricService.this.checkPermission();
            } else {
                Slog.w(BiometricService.TAG, "User " + callingUserId + " is requesting authentication of userid: " + userId);
                BiometricService.this.checkInternalPermission();
            }
            if (token == null || receiver == null || opPackageName == null || bundle == null) {
                Slog.e(BiometricService.TAG, "Unable to authenticate, one or more null arguments");
                return;
            }
            boolean isFromConfirmDeviceCredential = bundle.getBoolean("from_confirm_device_credential", false);
            if (isFromConfirmDeviceCredential) {
                BiometricService.this.checkInternalPermission();
            }
            boolean useDefaultTitle = bundle.getBoolean("use_default_title", false);
            if (useDefaultTitle) {
                BiometricService.this.checkInternalPermission();
                if (TextUtils.isEmpty(bundle.getCharSequence("title"))) {
                    bundle.putCharSequence("title", BiometricService.this.getContext().getString(17039609));
                }
            }
            if (bundle.getBoolean("allow_device_credential")) {
                BiometricService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.-$$Lambda$BiometricService$BiometricServiceWrapper$WcflArFV4_Tp6xBU53c-nQEP7Ro
                    @Override // java.lang.Runnable
                    public final void run() {
                        BiometricService.BiometricServiceWrapper.this.lambda$authenticate$0$BiometricService$BiometricServiceWrapper(receiver, userId, bundle);
                    }
                });
                return;
            }
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = token;
            args.arg2 = Long.valueOf(sessionId);
            args.argi1 = userId;
            args.arg3 = receiver;
            args.arg4 = opPackageName;
            args.arg5 = bundle;
            args.argi2 = callingUid;
            args.argi3 = callingPid;
            args.argi4 = callingUserId;
            args.arg6 = callback;
            BiometricService.this.mHandler.obtainMessage(9, args).sendToTarget();
        }

        public /* synthetic */ void lambda$authenticate$0$BiometricService$BiometricServiceWrapper(IBiometricServiceReceiver receiver, int userId, Bundle bundle) {
            KeyguardManager kgm = (KeyguardManager) BiometricService.this.getContext().getSystemService(KeyguardManager.class);
            if (kgm.isDeviceSecure()) {
                BiometricService.this.mConfirmDeviceCredentialReceiver = receiver;
                Intent intent = kgm.createConfirmDeviceCredentialIntent(null, null, userId);
                intent.putExtra("android.app.extra.BIOMETRIC_PROMPT_BUNDLE", bundle);
                intent.setFlags(134742016);
                BiometricService.this.getContext().startActivityAsUser(intent, UserHandle.CURRENT);
                return;
            }
            try {
                receiver.onError(14, BiometricService.this.getContext().getString(17039611));
            } catch (RemoteException e) {
                Slog.e(BiometricService.TAG, "Remote exception", e);
            }
        }

        public void onConfirmDeviceCredentialSuccess() {
            BiometricService.this.checkInternalPermission();
            BiometricService.this.mHandler.sendEmptyMessage(11);
        }

        public void onConfirmDeviceCredentialError(int error, String message) {
            BiometricService.this.checkInternalPermission();
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = error;
            args.arg1 = message;
            BiometricService.this.mHandler.obtainMessage(12, args).sendToTarget();
        }

        public void registerCancellationCallback(IBiometricConfirmDeviceCredentialCallback callback) {
            BiometricService.this.checkInternalPermission();
            BiometricService.this.mHandler.obtainMessage(13, callback).sendToTarget();
        }

        public void cancelAuthentication(IBinder token, String opPackageName) throws RemoteException {
            BiometricService.this.checkPermission();
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = token;
            args.arg2 = opPackageName;
            BiometricService.this.mHandler.obtainMessage(10, args).sendToTarget();
        }

        public int canAuthenticate(String opPackageName, int userId) {
            Slog.d(BiometricService.TAG, "canAuthenticate: User=" + userId + ", Caller=" + UserHandle.getCallingUserId());
            if (userId != UserHandle.getCallingUserId()) {
                BiometricService.this.checkInternalPermission();
            } else {
                BiometricService.this.checkPermission();
            }
            long ident = Binder.clearCallingIdentity();
            try {
                Pair<Integer, Integer> result = BiometricService.this.checkAndGetBiometricModality(userId);
                int error = ((Integer) result.second).intValue();
                return error;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        public boolean hasEnrolledBiometrics(int userId) {
            BiometricService.this.checkInternalPermission();
            long ident = Binder.clearCallingIdentity();
            for (int i = 0; i < BiometricService.this.mAuthenticators.size(); i++) {
                try {
                    if (BiometricService.this.mAuthenticators.get(i).mAuthenticator.hasEnrolledTemplates(userId)) {
                        Binder.restoreCallingIdentity(ident);
                        return true;
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
            return false;
        }

        public void registerEnabledOnKeyguardCallback(IBiometricEnabledOnKeyguardCallback callback) throws RemoteException {
            BiometricService.this.checkInternalPermission();
            BiometricService.this.mEnabledOnKeyguardCallbacks.add(new EnabledOnKeyguardCallback(callback));
            try {
                callback.onChanged(BiometricSourceType.FACE, BiometricService.this.mSettingObserver.getFaceEnabledOnKeyguard(), UserHandle.getCallingUserId());
            } catch (RemoteException e) {
                Slog.w(BiometricService.TAG, "Remote exception", e);
            }
        }

        public void setActiveUser(int userId) {
            BiometricService.this.checkInternalPermission();
            long ident = Binder.clearCallingIdentity();
            for (int i = 0; i < BiometricService.this.mAuthenticators.size(); i++) {
                try {
                    BiometricService.this.mAuthenticators.get(i).getAuthenticator().setActiveUser(userId);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        public void resetLockout(byte[] token) {
            BiometricService.this.checkInternalPermission();
            long ident = Binder.clearCallingIdentity();
            try {
                try {
                    if (BiometricService.this.mFingerprintService != null) {
                        BiometricService.this.mFingerprintService.resetTimeout(token);
                    }
                    if (BiometricService.this.mFaceService != null) {
                        BiometricService.this.mFaceService.resetLockout(token);
                    }
                } catch (RemoteException e) {
                    Slog.e(BiometricService.TAG, "Remote exception", e);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private void checkAppOp(String opPackageName, int callingUid) {
        if (this.mAppOps.noteOp(78, callingUid, opPackageName) != 0) {
            Slog.w(TAG, "Rejecting " + opPackageName + "; permission denied");
            throw new SecurityException("Permission denied");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkInternalPermission() {
        getContext().enforceCallingOrSelfPermission("android.permission.USE_BIOMETRIC_INTERNAL", "Must have USE_BIOMETRIC_INTERNAL permission");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkPermission() {
        if (getContext().checkCallingOrSelfPermission("android.permission.USE_FINGERPRINT") != 0) {
            getContext().enforceCallingOrSelfPermission("android.permission.USE_BIOMETRIC", "Must have USE_BIOMETRIC permission");
        }
    }

    public BiometricService(Context context) {
        super(context);
        this.mTaskStackListener = new BiometricTaskStackListener();
        this.mRandom = new Random();
        this.mAuthenticators = new ArrayList<>();
        this.mHandler = new Handler(Looper.getMainLooper()) { // from class: com.android.server.biometrics.BiometricService.1
            @Override // android.os.Handler
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case 1:
                        BiometricService.this.handleTaskStackChanged();
                        return;
                    case 2:
                        SomeArgs args = (SomeArgs) msg.obj;
                        BiometricService.this.handleAuthenticationSucceeded(((Boolean) args.arg1).booleanValue(), (byte[]) args.arg2);
                        args.recycle();
                        return;
                    case 3:
                        BiometricService.this.handleAuthenticationFailed((String) msg.obj);
                        return;
                    case 4:
                        SomeArgs args2 = (SomeArgs) msg.obj;
                        BiometricService.this.handleOnError(args2.argi1, args2.argi2, (String) args2.arg1);
                        args2.recycle();
                        return;
                    case 5:
                        SomeArgs args3 = (SomeArgs) msg.obj;
                        BiometricService.this.handleOnAcquired(args3.argi1, (String) args3.arg1);
                        args3.recycle();
                        return;
                    case 6:
                        BiometricService.this.handleOnDismissed(msg.arg1);
                        return;
                    case 7:
                        BiometricService.this.handleOnTryAgainPressed();
                        return;
                    case 8:
                        SomeArgs args4 = (SomeArgs) msg.obj;
                        BiometricService.this.handleOnReadyForAuthentication(args4.argi1, ((Boolean) args4.arg1).booleanValue(), args4.argi2);
                        args4.recycle();
                        return;
                    case 9:
                        SomeArgs args5 = (SomeArgs) msg.obj;
                        BiometricService.this.handleAuthenticate((IBinder) args5.arg1, ((Long) args5.arg2).longValue(), args5.argi1, (IBiometricServiceReceiver) args5.arg3, (String) args5.arg4, (Bundle) args5.arg5, args5.argi2, args5.argi3, args5.argi4, (IBiometricConfirmDeviceCredentialCallback) args5.arg6);
                        args5.recycle();
                        return;
                    case 10:
                        SomeArgs args6 = (SomeArgs) msg.obj;
                        BiometricService.this.handleCancelAuthentication((IBinder) args6.arg1, (String) args6.arg2);
                        args6.recycle();
                        return;
                    case 11:
                        BiometricService.this.handleOnConfirmDeviceCredentialSuccess();
                        return;
                    case 12:
                        SomeArgs args7 = (SomeArgs) msg.obj;
                        BiometricService.this.handleOnConfirmDeviceCredentialError(args7.argi1, (String) args7.arg1);
                        args7.recycle();
                        return;
                    case 13:
                        BiometricService.this.handleRegisterCancellationCallback((IBiometricConfirmDeviceCredentialCallback) msg.obj);
                        return;
                    default:
                        Slog.e(BiometricService.TAG, "Unknown message: " + msg);
                        return;
                }
            }
        };
        this.mInternalReceiver = new IBiometricServiceReceiverInternal.Stub() { // from class: com.android.server.biometrics.BiometricService.2
            public void onAuthenticationSucceeded(boolean requireConfirmation, byte[] token) throws RemoteException {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = Boolean.valueOf(requireConfirmation);
                args.arg2 = token;
                BiometricService.this.mHandler.obtainMessage(2, args).sendToTarget();
            }

            public void onAuthenticationFailed(int cookie, boolean requireConfirmation) throws RemoteException {
                String failureReason = BiometricService.this.getContext().getString(17039614);
                BiometricService.this.mHandler.obtainMessage(3, failureReason).sendToTarget();
            }

            public void onError(int cookie, int error, String message) throws RemoteException {
                if (error == 3) {
                    BiometricService.this.mHandler.obtainMessage(3, message).sendToTarget();
                    return;
                }
                SomeArgs args = SomeArgs.obtain();
                args.argi1 = cookie;
                args.argi2 = error;
                args.arg1 = message;
                BiometricService.this.mHandler.obtainMessage(4, args).sendToTarget();
            }

            public void onAcquired(int acquiredInfo, String message) throws RemoteException {
                SomeArgs args = SomeArgs.obtain();
                args.argi1 = acquiredInfo;
                args.arg1 = message;
                BiometricService.this.mHandler.obtainMessage(5, args).sendToTarget();
            }

            public void onDialogDismissed(int reason) throws RemoteException {
                BiometricService.this.mHandler.obtainMessage(6, reason, 0).sendToTarget();
            }

            public void onTryAgainPressed() {
                BiometricService.this.mHandler.sendEmptyMessage(7);
            }
        };
        this.mAppOps = (AppOpsManager) context.getSystemService(AppOpsManager.class);
        this.mEnabledOnKeyguardCallbacks = new ArrayList();
        this.mSettingObserver = new SettingObserver(this.mHandler);
        PackageManager pm = context.getPackageManager();
        this.mHasFeatureFingerprint = pm.hasSystemFeature("android.hardware.fingerprint");
        this.mHasFeatureIris = pm.hasSystemFeature("android.hardware.biometrics.iris");
        this.mHasFeatureFace = pm.hasSystemFeature("android.hardware.biometrics.face");
        try {
            ActivityManager.getService().registerUserSwitchObserver(new UserSwitchObserver() { // from class: com.android.server.biometrics.BiometricService.3
                public void onUserSwitchComplete(int newUserId) {
                    BiometricService.this.mSettingObserver.updateContentObserver();
                    BiometricService.this.mSettingObserver.notifyEnabledOnKeyguardCallbacks(newUserId);
                }
            }, BiometricService.class.getName());
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to register user switch observer", e);
        }
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        if (this.mHasFeatureFingerprint) {
            this.mFingerprintService = IFingerprintService.Stub.asInterface(ServiceManager.getService("fingerprint"));
        }
        if (this.mHasFeatureFace) {
            this.mFaceService = IFaceService.Stub.asInterface(ServiceManager.getService("face"));
        }
        this.mActivityTaskManager = ActivityTaskManager.getService();
        this.mStatusBarService = IStatusBarService.Stub.asInterface(ServiceManager.getService("statusbar"));
        int i = 0;
        while (true) {
            int[] iArr = FEATURE_ID;
            if (i < iArr.length) {
                if (hasFeature(iArr[i])) {
                    int[] iArr2 = FEATURE_ID;
                    Authenticator authenticator = new Authenticator(iArr2[i], getAuthenticator(iArr2[i]));
                    this.mAuthenticators.add(authenticator);
                }
                i++;
            } else {
                publishBinderService("biometric", new BiometricServiceWrapper());
                return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public Pair<Integer, Integer> checkAndGetBiometricModality(int userId) {
        int modality = 0;
        if (this.mAuthenticators.isEmpty()) {
            return new Pair<>(0, 12);
        }
        boolean isHardwareDetected = false;
        boolean hasTemplatesEnrolled = false;
        boolean enabledForApps = false;
        int firstHwAvailable = 0;
        int i = 0;
        while (true) {
            if (i >= this.mAuthenticators.size()) {
                break;
            }
            modality = this.mAuthenticators.get(i).getType();
            BiometricAuthenticator authenticator = this.mAuthenticators.get(i).getAuthenticator();
            if (authenticator.isHardwareDetected()) {
                isHardwareDetected = true;
                if (firstHwAvailable == 0) {
                    firstHwAvailable = modality;
                }
                if (authenticator.hasEnrolledTemplates(userId)) {
                    hasTemplatesEnrolled = true;
                    if (isEnabledForApp(modality, userId)) {
                        enabledForApps = true;
                        break;
                    }
                } else {
                    continue;
                }
            }
            i++;
        }
        Slog.d(TAG, "checkAndGetBiometricModality: user=" + userId + " isHardwareDetected=" + isHardwareDetected + " hasTemplatesEnrolled=" + hasTemplatesEnrolled + " enabledForApps=" + enabledForApps);
        if (!isHardwareDetected) {
            return new Pair<>(0, 1);
        }
        if (!hasTemplatesEnrolled) {
            return new Pair<>(Integer.valueOf(firstHwAvailable), 11);
        }
        if (!enabledForApps) {
            return new Pair<>(0, 1);
        }
        return new Pair<>(Integer.valueOf(modality), 0);
    }

    private boolean isEnabledForApp(int modality, int userId) {
        if (modality == 1 || modality == 2) {
            return true;
        }
        if (modality == 4) {
            return this.mSettingObserver.getFaceEnabledForApps(userId);
        }
        Slog.w(TAG, "Unsupported modality: " + modality);
        return false;
    }

    private String getErrorString(int type, int error, int vendorCode) {
        if (type != 1) {
            if (type == 2) {
                Slog.w(TAG, "Modality not supported");
                return null;
            } else if (type == 4) {
                return FaceManager.getErrorString(getContext(), error, vendorCode);
            } else {
                Slog.w(TAG, "Unable to get error string for modality: " + type);
                return null;
            }
        }
        return FingerprintManager.getErrorString(getContext(), error, vendorCode);
    }

    private BiometricAuthenticator getAuthenticator(int type) {
        if (type != 1) {
            if (type == 2 || type != 4) {
                return null;
            }
            return (FaceManager) getContext().getSystemService("face");
        }
        return (FingerprintManager) getContext().getSystemService("fingerprint");
    }

    private boolean hasFeature(int type) {
        if (type != 1) {
            if (type != 2) {
                if (type == 4) {
                    return this.mHasFeatureFace;
                }
                return false;
            }
            return this.mHasFeatureIris;
        }
        return this.mHasFeatureFingerprint;
    }

    private void logDialogDismissed(int reason) {
        int error;
        if (reason == 1) {
            long latency = System.currentTimeMillis() - this.mCurrentAuthSession.mAuthenticatedTimeMs;
            StatsLog.write(88, statsModality(), this.mCurrentAuthSession.mUserId, this.mCurrentAuthSession.isCrypto(), 2, this.mCurrentAuthSession.mRequireConfirmation, 3, latency, Utils.isDebugEnabled(getContext(), this.mCurrentAuthSession.mUserId));
            return;
        }
        long latency2 = System.currentTimeMillis() - this.mCurrentAuthSession.mStartTimeMs;
        if (reason == 2) {
            error = 13;
        } else if (reason == 3) {
            error = 10;
        } else {
            error = 0;
        }
        StatsLog.write(89, statsModality(), this.mCurrentAuthSession.mUserId, this.mCurrentAuthSession.isCrypto(), 2, 2, error, 0, Utils.isDebugEnabled(getContext(), this.mCurrentAuthSession.mUserId), latency2);
    }

    private int statsModality() {
        int modality = 0;
        AuthSession authSession = this.mCurrentAuthSession;
        if (authSession == null) {
            return 0;
        }
        if ((authSession.mModality & 1) != 0) {
            modality = 0 | 1;
        }
        if ((this.mCurrentAuthSession.mModality & 2) != 0) {
            modality |= 2;
        }
        if ((this.mCurrentAuthSession.mModality & 4) != 0) {
            return modality | 4;
        }
        return modality;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleTaskStackChanged() {
        try {
            List<ActivityManager.RunningTaskInfo> runningTasks = this.mActivityTaskManager.getTasks(1);
            if (!runningTasks.isEmpty()) {
                String topPackage = runningTasks.get(0).topActivity.getPackageName();
                if (this.mCurrentAuthSession != null && !topPackage.contentEquals(this.mCurrentAuthSession.mOpPackageName)) {
                    this.mStatusBarService.hideBiometricDialog();
                    this.mActivityTaskManager.unregisterTaskStackListener(this.mTaskStackListener);
                    this.mCurrentAuthSession.mClientReceiver.onError(5, getContext().getString(17039610));
                    this.mCurrentAuthSession.mState = 0;
                    this.mCurrentAuthSession = null;
                }
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to get running tasks", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleAuthenticationSucceeded(boolean requireConfirmation, byte[] token) {
        try {
            if (this.mCurrentAuthSession == null) {
                Slog.e(TAG, "onAuthenticationSucceeded(): Auth session is null");
                return;
            }
            if (!requireConfirmation) {
                this.mActivityTaskManager.unregisterTaskStackListener(this.mTaskStackListener);
                KeyStore.getInstance().addAuthToken(token);
                this.mCurrentAuthSession.mClientReceiver.onAuthenticationSucceeded();
                this.mCurrentAuthSession.mState = 0;
                this.mCurrentAuthSession = null;
            } else {
                this.mCurrentAuthSession.mAuthenticatedTimeMs = System.currentTimeMillis();
                this.mCurrentAuthSession.mTokenEscrow = token;
                this.mCurrentAuthSession.mState = 5;
            }
            this.mStatusBarService.onBiometricAuthenticated(true, (String) null);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleAuthenticationFailed(String failureReason) {
        try {
            if (this.mCurrentAuthSession == null) {
                Slog.e(TAG, "onAuthenticationFailed(): Auth session is null");
                return;
            }
            this.mStatusBarService.onBiometricAuthenticated(false, failureReason);
            if ((this.mCurrentAuthSession.mModality & 4) != 0) {
                this.mCurrentAuthSession.mState = 3;
            }
            this.mCurrentAuthSession.mClientReceiver.onAuthenticationFailed();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleOnConfirmDeviceCredentialSuccess() {
        if (this.mConfirmDeviceCredentialReceiver == null) {
            Slog.w(TAG, "onCDCASuccess null!");
            return;
        }
        try {
            this.mActivityTaskManager.unregisterTaskStackListener(this.mTaskStackListener);
            this.mConfirmDeviceCredentialReceiver.onAuthenticationSucceeded();
            if (this.mCurrentAuthSession != null) {
                this.mCurrentAuthSession.mState = 0;
                this.mCurrentAuthSession = null;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
        }
        this.mConfirmDeviceCredentialReceiver = null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleOnConfirmDeviceCredentialError(int error, String message) {
        if (this.mConfirmDeviceCredentialReceiver == null) {
            Slog.w(TAG, "onCDCAError null! Error: " + error + " " + message);
            return;
        }
        try {
            this.mActivityTaskManager.unregisterTaskStackListener(this.mTaskStackListener);
            this.mConfirmDeviceCredentialReceiver.onError(error, message);
            if (this.mCurrentAuthSession != null) {
                this.mCurrentAuthSession.mState = 0;
                this.mCurrentAuthSession = null;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
        }
        this.mConfirmDeviceCredentialReceiver = null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleRegisterCancellationCallback(IBiometricConfirmDeviceCredentialCallback callback) {
        if (this.mCurrentAuthSession == null) {
            Slog.d(TAG, "Current auth session null");
            return;
        }
        Slog.d(TAG, "Updating cancel callback");
        this.mCurrentAuthSession.mConfirmDeviceCredentialCallback = callback;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleOnError(int cookie, final int error, final String message) {
        Slog.d(TAG, "Error: " + error + " cookie: " + cookie);
        try {
            if (this.mCurrentAuthSession != null && this.mCurrentAuthSession.containsCookie(cookie)) {
                if (!this.mCurrentAuthSession.isFromConfirmDeviceCredential()) {
                    if (this.mCurrentAuthSession.mState != 2) {
                        if (this.mCurrentAuthSession.mState == 3) {
                            this.mCurrentAuthSession.mClientReceiver.onError(error, message);
                            this.mStatusBarService.onBiometricError(message);
                            this.mActivityTaskManager.unregisterTaskStackListener(this.mTaskStackListener);
                            this.mCurrentAuthSession.mState = 0;
                            this.mCurrentAuthSession = null;
                        } else {
                            Slog.e(TAG, "Impossible session error state: " + this.mCurrentAuthSession.mState);
                        }
                    } else {
                        this.mStatusBarService.onBiometricError(message);
                        if (error == 5) {
                            this.mActivityTaskManager.unregisterTaskStackListener(this.mTaskStackListener);
                            this.mCurrentAuthSession.mClientReceiver.onError(error, message);
                            this.mCurrentAuthSession.mState = 0;
                            this.mCurrentAuthSession = null;
                            this.mStatusBarService.hideBiometricDialog();
                        } else {
                            this.mHandler.postDelayed(new Runnable() { // from class: com.android.server.biometrics.-$$Lambda$BiometricService$QhCJhzC2Bjg3cY0zTVc1KBEEOuA
                                @Override // java.lang.Runnable
                                public final void run() {
                                    BiometricService.this.lambda$handleOnError$0$BiometricService(error, message);
                                }
                            }, 2000L);
                        }
                    }
                } else {
                    Slog.d(TAG, "From CDC, transition to CANCELED_SHOWING_CDC state");
                    this.mCurrentAuthSession.mClientReceiver.onError(error, message);
                    this.mCurrentAuthSession.mState = 6;
                    this.mStatusBarService.hideBiometricDialog();
                }
            } else if (this.mPendingAuthSession != null && this.mPendingAuthSession.containsCookie(cookie)) {
                if (this.mPendingAuthSession.mState == 1) {
                    this.mPendingAuthSession.mClientReceiver.onError(error, message);
                    this.mPendingAuthSession.mState = 0;
                    this.mPendingAuthSession = null;
                } else {
                    Slog.e(TAG, "Impossible pending session error state: " + this.mPendingAuthSession.mState);
                }
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    public /* synthetic */ void lambda$handleOnError$0$BiometricService(int error, String message) {
        try {
            if (this.mCurrentAuthSession != null) {
                this.mActivityTaskManager.unregisterTaskStackListener(this.mTaskStackListener);
                this.mCurrentAuthSession.mClientReceiver.onError(error, message);
                this.mCurrentAuthSession.mState = 0;
                this.mCurrentAuthSession = null;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleOnAcquired(int acquiredInfo, String message) {
        if (this.mCurrentAuthSession == null) {
            Slog.e(TAG, "onAcquired(): Auth session is null");
        } else if (acquiredInfo != 0) {
            if (message == null) {
                Slog.w(TAG, "Ignoring null message: " + acquiredInfo);
                return;
            }
            try {
                this.mStatusBarService.onBiometricHelp(message);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleOnDismissed(int reason) {
        if (this.mCurrentAuthSession == null) {
            Slog.e(TAG, "onDialogDismissed: " + reason + ", auth session null");
            return;
        }
        logDialogDismissed(reason);
        if (reason != 1) {
            try {
                this.mCurrentAuthSession.mClientReceiver.onDialogDismissed(reason);
                cancelInternal(null, null, false);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
                return;
            }
        }
        if (reason == 3) {
            this.mCurrentAuthSession.mClientReceiver.onError(10, getContext().getString(17039613));
        } else if (reason == 1) {
            KeyStore.getInstance().addAuthToken(this.mCurrentAuthSession.mTokenEscrow);
            this.mCurrentAuthSession.mClientReceiver.onAuthenticationSucceeded();
        }
        if (!this.mCurrentAuthSession.isFromConfirmDeviceCredential()) {
            this.mActivityTaskManager.unregisterTaskStackListener(this.mTaskStackListener);
            this.mCurrentAuthSession.mState = 0;
            this.mCurrentAuthSession = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleOnTryAgainPressed() {
        Slog.d(TAG, "onTryAgainPressed");
        authenticateInternal(this.mCurrentAuthSession.mToken, this.mCurrentAuthSession.mSessionId, this.mCurrentAuthSession.mUserId, this.mCurrentAuthSession.mClientReceiver, this.mCurrentAuthSession.mOpPackageName, this.mCurrentAuthSession.mBundle, this.mCurrentAuthSession.mCallingUid, this.mCurrentAuthSession.mCallingPid, this.mCurrentAuthSession.mCallingUserId, this.mCurrentAuthSession.mModality, this.mCurrentAuthSession.mConfirmDeviceCredentialCallback);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleOnReadyForAuthentication(int cookie, boolean requireConfirmation, int userId) {
        Iterator it = this.mPendingAuthSession.mModalitiesWaiting.entrySet().iterator();
        while (true) {
            if (!it.hasNext()) {
                break;
            }
            Map.Entry<Integer, Integer> pair = it.next();
            if (pair.getValue().intValue() == cookie) {
                this.mPendingAuthSession.mModalitiesMatched.put(pair.getKey(), pair.getValue());
                this.mPendingAuthSession.mModalitiesWaiting.remove(pair.getKey());
                Slog.d(TAG, "Matched cookie: " + cookie + ", " + this.mPendingAuthSession.mModalitiesWaiting.size() + " remaining");
                break;
            }
        }
        if (this.mPendingAuthSession.mModalitiesWaiting.isEmpty()) {
            AuthSession authSession = this.mCurrentAuthSession;
            boolean continuing = authSession != null && authSession.mState == 3;
            this.mCurrentAuthSession = this.mPendingAuthSession;
            this.mCurrentAuthSession.mStartTimeMs = System.currentTimeMillis();
            this.mPendingAuthSession = null;
            this.mCurrentAuthSession.mState = 2;
            int modality = 0;
            try {
                for (Map.Entry<Integer, Integer> pair2 : this.mCurrentAuthSession.mModalitiesMatched.entrySet()) {
                    if (pair2.getKey().intValue() == 1) {
                        this.mFingerprintService.startPreparedClient(pair2.getValue().intValue());
                    } else if (pair2.getKey().intValue() == 2) {
                        Slog.e(TAG, "Iris unsupported");
                    } else if (pair2.getKey().intValue() == 4) {
                        this.mFaceService.startPreparedClient(pair2.getValue().intValue());
                    } else {
                        Slog.e(TAG, "Unknown modality: " + pair2.getKey());
                    }
                    modality |= pair2.getKey().intValue();
                }
                if (!continuing) {
                    this.mStatusBarService.showBiometricDialog(this.mCurrentAuthSession.mBundle, this.mInternalReceiver, modality, requireConfirmation, userId);
                    this.mActivityTaskManager.registerTaskStackListener(this.mTaskStackListener);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleAuthenticate(final IBinder token, final long sessionId, final int userId, final IBiometricServiceReceiver receiver, final String opPackageName, final Bundle bundle, final int callingUid, final int callingPid, final int callingUserId, final IBiometricConfirmDeviceCredentialCallback callback) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.-$$Lambda$BiometricService$u838xLmNIeU4FVoszS6ZOdfG9A8
            @Override // java.lang.Runnable
            public final void run() {
                BiometricService.this.lambda$handleAuthenticate$1$BiometricService(userId, receiver, token, sessionId, opPackageName, bundle, callingUid, callingPid, callingUserId, callback);
            }
        });
    }

    public /* synthetic */ void lambda$handleAuthenticate$1$BiometricService(int userId, IBiometricServiceReceiver receiver, IBinder token, long sessionId, String opPackageName, Bundle bundle, int callingUid, int callingPid, int callingUserId, IBiometricConfirmDeviceCredentialCallback callback) {
        Pair<Integer, Integer> result = checkAndGetBiometricModality(userId);
        int modality = ((Integer) result.first).intValue();
        int error = ((Integer) result.second).intValue();
        if (error != 0) {
            try {
                String hardwareUnavailable = getContext().getString(17039612);
                if (error == 1) {
                    receiver.onError(error, hardwareUnavailable);
                } else if (error == 11) {
                    receiver.onError(error, getErrorString(modality, error, 0));
                } else if (error == 12) {
                    receiver.onError(error, hardwareUnavailable);
                } else {
                    Slog.e(TAG, "Unhandled error");
                }
                return;
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to send error", e);
                return;
            }
        }
        this.mCurrentModality = modality;
        authenticateInternal(token, sessionId, userId, receiver, opPackageName, bundle, callingUid, callingPid, callingUserId, modality, callback);
    }

    private void authenticateInternal(IBinder token, long sessionId, int userId, IBiometricServiceReceiver receiver, String opPackageName, Bundle bundle, int callingUid, int callingPid, int callingUserId, int modality, IBiometricConfirmDeviceCredentialCallback callback) {
        String str;
        boolean z;
        try {
            boolean requireConfirmation = bundle.getBoolean("require_confirmation", true);
            if ((modality & 4) != 0) {
                if (!requireConfirmation) {
                    try {
                        if (!this.mSettingObserver.getFaceAlwaysRequireConfirmation(userId)) {
                            z = false;
                            requireConfirmation = z;
                        }
                    } catch (RemoteException e) {
                        e = e;
                        str = TAG;
                        Slog.e(str, "Unable to start authentication", e);
                    }
                }
                z = true;
                requireConfirmation = z;
            }
            int cookie = this.mRandom.nextInt(2147483646) + 1;
            Slog.d(TAG, "Creating auth session. Modality: " + modality + ", cookie: " + cookie);
            HashMap<Integer, Integer> authenticators = new HashMap<>();
            authenticators.put(Integer.valueOf(modality), Integer.valueOf(cookie));
            try {
            } catch (RemoteException e2) {
                e = e2;
            }
            try {
                this.mPendingAuthSession = new AuthSession(authenticators, token, sessionId, userId, receiver, opPackageName, bundle, callingUid, callingPid, callingUserId, modality, requireConfirmation, callback);
                this.mPendingAuthSession.mState = 1;
                if ((modality & 1) != 0) {
                    this.mFingerprintService.prepareForAuthentication(token, sessionId, userId, this.mInternalReceiver, opPackageName, cookie, callingUid, callingPid, callingUserId);
                }
                if ((modality & 2) == 0) {
                    str = TAG;
                } else {
                    str = TAG;
                    try {
                        Slog.w(str, "Iris unsupported");
                    } catch (RemoteException e3) {
                        e = e3;
                        Slog.e(str, "Unable to start authentication", e);
                    }
                }
                if ((modality & 4) != 0) {
                    this.mFaceService.prepareForAuthentication(requireConfirmation, token, sessionId, userId, this.mInternalReceiver, opPackageName, cookie, callingUid, callingPid, callingUserId);
                }
            } catch (RemoteException e4) {
                e = e4;
                str = TAG;
                Slog.e(str, "Unable to start authentication", e);
            }
        } catch (RemoteException e5) {
            e = e5;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleCancelAuthentication(IBinder token, String opPackageName) {
        if (token == null || opPackageName == null) {
            Slog.e(TAG, "Unable to cancel, one or more null arguments");
            return;
        }
        AuthSession authSession = this.mCurrentAuthSession;
        if (authSession != null && authSession.mState == 6) {
            Slog.d(TAG, "Cancel received while ConfirmDeviceCredential showing");
            try {
                this.mCurrentAuthSession.mConfirmDeviceCredentialCallback.cancel();
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to cancel ConfirmDeviceCredential", e);
            }
            handleOnConfirmDeviceCredentialError(5, getContext().getString(17039610));
            return;
        }
        AuthSession authSession2 = this.mCurrentAuthSession;
        if (authSession2 != null && authSession2.mState != 2) {
            try {
                this.mCurrentAuthSession.mClientReceiver.onError(5, getContext().getString(17039613));
                this.mCurrentAuthSession.mState = 0;
                this.mCurrentAuthSession = null;
                this.mStatusBarService.hideBiometricDialog();
                return;
            } catch (RemoteException e2) {
                Slog.e(TAG, "Remote exception", e2);
                return;
            }
        }
        boolean fromCDC = false;
        AuthSession authSession3 = this.mCurrentAuthSession;
        if (authSession3 != null) {
            fromCDC = authSession3.mBundle.getBoolean("from_confirm_device_credential", false);
        }
        if (fromCDC) {
            Slog.d(TAG, "Cancelling from CDC");
            cancelInternal(token, opPackageName, false);
            return;
        }
        cancelInternal(token, opPackageName, true);
    }

    void cancelInternal(final IBinder token, final String opPackageName, final boolean fromClient) {
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final int callingUserId = UserHandle.getCallingUserId();
        this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.-$$Lambda$BiometricService$_uy2KOpZAFuLlEdqD9ofDZtdKeQ
            @Override // java.lang.Runnable
            public final void run() {
                BiometricService.this.lambda$cancelInternal$2$BiometricService(token, opPackageName, callingUid, callingPid, callingUserId, fromClient);
            }
        });
    }

    public /* synthetic */ void lambda$cancelInternal$2$BiometricService(IBinder token, String opPackageName, int callingUid, int callingPid, int callingUserId, boolean fromClient) {
        try {
            if ((this.mCurrentModality & 1) != 0) {
                this.mFingerprintService.cancelAuthenticationFromService(token, opPackageName, callingUid, callingPid, callingUserId, fromClient);
            }
            if ((this.mCurrentModality & 2) != 0) {
                Slog.w(TAG, "Iris unsupported");
            }
            if ((this.mCurrentModality & 4) != 0) {
                this.mFaceService.cancelAuthenticationFromService(token, opPackageName, callingUid, callingPid, callingUserId, fromClient);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to cancel authentication");
        }
    }
}
