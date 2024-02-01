package com.android.server.media;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.INotificationManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioSystem;
import android.media.IAudioService;
import android.media.IRemoteVolumeController;
import android.media.ISessionTokensListener;
import android.media.MediaController2;
import android.media.SessionToken2;
import android.media.session.IActiveSessionsListener;
import android.media.session.ICallback;
import android.media.session.IOnMediaKeyListener;
import android.media.session.IOnVolumeKeyLongPressListener;
import android.media.session.ISession;
import android.media.session.ISessionCallback;
import android.media.session.ISessionManager;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;
import com.android.server.Watchdog;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.media.AudioPlayerStateMonitor;
import com.android.server.media.MediaSessionStack;
import com.android.server.wm.WindowManagerService;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
/* loaded from: classes.dex */
public class MediaSessionService extends SystemService implements Watchdog.Monitor {
    private static final boolean DEBUG_KEY_EVENT = true;
    private static final int MEDIA_KEY_LISTENER_TIMEOUT = 1000;
    static final boolean USE_MEDIA2_APIS = false;
    private static final int WAKELOCK_TIMEOUT = 5000;
    private AudioPlayerStateMonitor mAudioPlayerStateMonitor;
    private IAudioService mAudioService;
    private ContentResolver mContentResolver;
    private FullUserRecord mCurrentFullUserRecord;
    private final SparseIntArray mFullUserIds;
    private MediaSessionRecord mGlobalPrioritySession;
    private final MessageHandler mHandler;
    private boolean mHasFeatureLeanback;
    private KeyguardManager mKeyguardManager;
    private final Object mLock;
    private final int mLongPressTimeout;
    private final PowerManager.WakeLock mMediaEventWakeLock;
    private final INotificationManager mNotificationManager;
    private final IPackageManager mPackageManager;
    private IRemoteVolumeController mRvc;
    private final SessionManagerImpl mSessionManagerImpl;
    private final Map<SessionToken2, MediaController2> mSessionRecords;
    private final List<SessionTokensListenerRecord> mSessionTokensListeners;
    private final ArrayList<SessionsListenerRecord> mSessionsListeners;
    private SettingsObserver mSettingsObserver;
    private final SparseArray<FullUserRecord> mUserRecords;
    private static final String TAG = "MediaSessionService";
    static final boolean DEBUG = Log.isLoggable(TAG, 3);

    public MediaSessionService(Context context) {
        super(context);
        this.mFullUserIds = new SparseIntArray();
        this.mUserRecords = new SparseArray<>();
        this.mSessionsListeners = new ArrayList<>();
        this.mLock = new Object();
        this.mHandler = new MessageHandler();
        this.mSessionRecords = new ArrayMap();
        this.mSessionTokensListeners = new ArrayList();
        this.mSessionManagerImpl = new SessionManagerImpl();
        PowerManager pm = (PowerManager) context.getSystemService("power");
        this.mMediaEventWakeLock = pm.newWakeLock(1, "handleMediaEvent");
        this.mLongPressTimeout = ViewConfiguration.getLongPressTimeout();
        this.mNotificationManager = INotificationManager.Stub.asInterface(ServiceManager.getService("notification"));
        this.mPackageManager = AppGlobals.getPackageManager();
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishBinderService("media_session", this.mSessionManagerImpl);
        Watchdog.getInstance().addMonitor(this);
        this.mKeyguardManager = (KeyguardManager) getContext().getSystemService("keyguard");
        this.mAudioService = getAudioService();
        this.mAudioPlayerStateMonitor = AudioPlayerStateMonitor.getInstance();
        this.mAudioPlayerStateMonitor.registerListener(new AudioPlayerStateMonitor.OnAudioPlayerActiveStateChangedListener() { // from class: com.android.server.media.-$$Lambda$MediaSessionService$za_9dlUSlnaiZw6eCdPVEZq0XLw
            @Override // com.android.server.media.AudioPlayerStateMonitor.OnAudioPlayerActiveStateChangedListener
            public final void onAudioPlayerActiveStateChanged(AudioPlaybackConfiguration audioPlaybackConfiguration, boolean z) {
                MediaSessionService.lambda$onStart$0(MediaSessionService.this, audioPlaybackConfiguration, z);
            }
        }, null);
        this.mAudioPlayerStateMonitor.registerSelfIntoAudioServiceIfNeeded(this.mAudioService);
        this.mContentResolver = getContext().getContentResolver();
        this.mSettingsObserver = new SettingsObserver();
        this.mSettingsObserver.observe();
        this.mHasFeatureLeanback = getContext().getPackageManager().hasSystemFeature("android.software.leanback");
        updateUser();
        registerPackageBroadcastReceivers();
        buildMediaSessionService2List();
    }

    public static /* synthetic */ void lambda$onStart$0(MediaSessionService mediaSessionService, AudioPlaybackConfiguration config, boolean isRemoved) {
        if (isRemoved || !config.isActive() || config.getPlayerType() == 3) {
            return;
        }
        synchronized (mediaSessionService.mLock) {
            FullUserRecord user = mediaSessionService.getFullUserRecordLocked(UserHandle.getUserId(config.getClientUid()));
            if (user != null) {
                user.mPriorityStack.updateMediaButtonSessionIfNeeded();
            }
        }
    }

    private IAudioService getAudioService() {
        IBinder b = ServiceManager.getService("audio");
        return IAudioService.Stub.asInterface(b);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isGlobalPriorityActiveLocked() {
        return this.mGlobalPrioritySession != null && this.mGlobalPrioritySession.isActive();
    }

    public void updateSession(MediaSessionRecord record) {
        synchronized (this.mLock) {
            FullUserRecord user = getFullUserRecordLocked(record.getUserId());
            if (user == null) {
                Log.w(TAG, "Unknown session updated. Ignoring.");
                return;
            }
            if ((record.getFlags() & 65536) != 0) {
                Log.d(TAG, "Global priority session is updated, active=" + record.isActive());
                user.pushAddressedPlayerChangedLocked();
            } else if (!user.mPriorityStack.contains(record)) {
                Log.w(TAG, "Unknown session updated. Ignoring.");
                return;
            } else {
                user.mPriorityStack.onSessionStateChange(record);
            }
            this.mHandler.postSessionsChanged(record.getUserId());
        }
    }

    public void setGlobalPrioritySession(MediaSessionRecord record) {
        synchronized (this.mLock) {
            FullUserRecord user = getFullUserRecordLocked(record.getUserId());
            if (this.mGlobalPrioritySession != record) {
                Log.d(TAG, "Global priority session is changed from " + this.mGlobalPrioritySession + " to " + record);
                this.mGlobalPrioritySession = record;
                if (user != null && user.mPriorityStack.contains(record)) {
                    user.mPriorityStack.removeSession(record);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public List<MediaSessionRecord> getActiveSessionsLocked(int userId) {
        List<MediaSessionRecord> records = new ArrayList<>();
        if (userId == -1) {
            int size = this.mUserRecords.size();
            for (int i = 0; i < size; i++) {
                records.addAll(this.mUserRecords.valueAt(i).mPriorityStack.getActiveSessions(userId));
            }
        } else {
            FullUserRecord user = getFullUserRecordLocked(userId);
            if (user == null) {
                Log.w(TAG, "getSessions failed. Unknown user " + userId);
                return records;
            }
            records.addAll(user.mPriorityStack.getActiveSessions(userId));
        }
        if (isGlobalPriorityActiveLocked() && (userId == -1 || userId == this.mGlobalPrioritySession.getUserId())) {
            records.add(0, this.mGlobalPrioritySession);
        }
        return records;
    }

    public void notifyRemoteVolumeChanged(int flags, MediaSessionRecord session) {
        if (this.mRvc == null || !session.isActive()) {
            return;
        }
        try {
            this.mRvc.remoteVolumeChanged(session.getControllerBinder(), flags);
        } catch (Exception e) {
            Log.wtf(TAG, "Error sending volume change to system UI.", e);
        }
    }

    public void onSessionPlaystateChanged(MediaSessionRecord record, int oldState, int newState) {
        synchronized (this.mLock) {
            FullUserRecord user = getFullUserRecordLocked(record.getUserId());
            if (user != null && user.mPriorityStack.contains(record)) {
                user.mPriorityStack.onPlaystateChanged(record, oldState, newState);
                return;
            }
            Log.d(TAG, "Unknown session changed playback state. Ignoring.");
        }
    }

    public void onSessionPlaybackTypeChanged(MediaSessionRecord record) {
        synchronized (this.mLock) {
            FullUserRecord user = getFullUserRecordLocked(record.getUserId());
            if (user != null && user.mPriorityStack.contains(record)) {
                pushRemoteVolumeUpdateLocked(record.getUserId());
                return;
            }
            Log.d(TAG, "Unknown session changed playback type. Ignoring.");
        }
    }

    @Override // com.android.server.SystemService
    public void onStartUser(int userId) {
        if (DEBUG) {
            Log.d(TAG, "onStartUser: " + userId);
        }
        updateUser();
    }

    @Override // com.android.server.SystemService
    public void onSwitchUser(int userId) {
        if (DEBUG) {
            Log.d(TAG, "onSwitchUser: " + userId);
        }
        updateUser();
    }

    @Override // com.android.server.SystemService
    public void onStopUser(int userId) {
        if (DEBUG) {
            Log.d(TAG, "onStopUser: " + userId);
        }
        synchronized (this.mLock) {
            FullUserRecord user = getFullUserRecordLocked(userId);
            if (user != null) {
                if (user.mFullUserId == userId) {
                    user.destroySessionsForUserLocked(-1);
                    this.mUserRecords.remove(userId);
                } else {
                    user.destroySessionsForUserLocked(userId);
                }
            }
            updateUser();
        }
    }

    @Override // com.android.server.Watchdog.Monitor
    public void monitor() {
        synchronized (this.mLock) {
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void enforcePhoneStatePermission(int pid, int uid) {
        if (getContext().checkPermission("android.permission.MODIFY_PHONE_STATE", pid, uid) != 0) {
            throw new SecurityException("Must hold the MODIFY_PHONE_STATE permission.");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sessionDied(MediaSessionRecord session) {
        synchronized (this.mLock) {
            destroySessionLocked(session);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void destroySession(MediaSessionRecord session) {
        synchronized (this.mLock) {
            destroySessionLocked(session);
        }
    }

    private void updateUser() {
        synchronized (this.mLock) {
            UserManager manager = (UserManager) getContext().getSystemService("user");
            this.mFullUserIds.clear();
            List<UserInfo> allUsers = manager.getUsers();
            if (allUsers != null) {
                for (UserInfo userInfo : allUsers) {
                    if (userInfo.isManagedProfile()) {
                        this.mFullUserIds.put(userInfo.id, userInfo.profileGroupId);
                    } else {
                        this.mFullUserIds.put(userInfo.id, userInfo.id);
                        if (this.mUserRecords.get(userInfo.id) == null) {
                            this.mUserRecords.put(userInfo.id, new FullUserRecord(userInfo.id));
                        }
                    }
                }
            }
            int currentFullUserId = ActivityManager.getCurrentUser();
            this.mCurrentFullUserRecord = this.mUserRecords.get(currentFullUserId);
            if (this.mCurrentFullUserRecord == null) {
                Log.w(TAG, "Cannot find FullUserInfo for the current user " + currentFullUserId);
                this.mCurrentFullUserRecord = new FullUserRecord(currentFullUserId);
                this.mUserRecords.put(currentFullUserId, this.mCurrentFullUserRecord);
            }
            this.mFullUserIds.put(currentFullUserId, currentFullUserId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateActiveSessionListeners() {
        synchronized (this.mLock) {
            for (int i = this.mSessionsListeners.size() - 1; i >= 0; i--) {
                SessionsListenerRecord listener = this.mSessionsListeners.get(i);
                try {
                    enforceMediaPermissions(listener.mComponentName, listener.mPid, listener.mUid, listener.mUserId);
                } catch (SecurityException e) {
                    Log.i(TAG, "ActiveSessionsListener " + listener.mComponentName + " is no longer authorized. Disconnecting.");
                    this.mSessionsListeners.remove(i);
                    try {
                        listener.mListener.onActiveSessionsChanged(new ArrayList());
                    } catch (Exception e2) {
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void destroySessionLocked(MediaSessionRecord session) {
        if (DEBUG) {
            Log.d(TAG, "Destroying " + session);
        }
        FullUserRecord user = getFullUserRecordLocked(session.getUserId());
        if (this.mGlobalPrioritySession == session) {
            this.mGlobalPrioritySession = null;
            if (session.isActive() && user != null) {
                user.pushAddressedPlayerChangedLocked();
            }
        } else if (user != null) {
            user.mPriorityStack.removeSession(session);
        }
        try {
            session.getCallback().asBinder().unlinkToDeath(session, 0);
        } catch (Exception e) {
        }
        session.onDestroy();
        this.mHandler.postSessionsChanged(session.getUserId());
    }

    private void registerPackageBroadcastReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addDataScheme("package");
        filter.addAction("android.intent.action.PACKAGE_ADDED");
        filter.addAction("android.intent.action.PACKAGE_REMOVED");
        filter.addAction("android.intent.action.PACKAGE_CHANGED");
        filter.addAction("android.intent.action.PACKAGES_SUSPENDED");
        filter.addAction("android.intent.action.PACKAGES_UNSUSPENDED");
        filter.addAction("android.intent.action.EXTERNAL_APPLICATIONS_AVAILABLE");
        filter.addAction("android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE");
        filter.addAction("android.intent.action.PACKAGE_REPLACED");
        getContext().registerReceiverAsUser(new BroadcastReceiver() { // from class: com.android.server.media.MediaSessionService.1
            /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
            /* JADX WARN: Code restructure failed: missing block: B:13:0x0055, code lost:
                if (r3.equals("android.intent.action.PACKAGE_ADDED") != false) goto L13;
             */
            @Override // android.content.BroadcastReceiver
            /*
                Code decompiled incorrectly, please refer to instructions dump.
                To view partially-correct add '--show-bad-code' argument
            */
            public void onReceive(android.content.Context r7, android.content.Intent r8) {
                /*
                    r6 = this;
                    java.lang.String r0 = "android.intent.extra.user_handle"
                    r1 = -10000(0xffffffffffffd8f0, float:NaN)
                    int r0 = r8.getIntExtra(r0, r1)
                    if (r0 != r1) goto L21
                    java.lang.String r1 = "MediaSessionService"
                    java.lang.StringBuilder r2 = new java.lang.StringBuilder
                    r2.<init>()
                    java.lang.String r3 = "Intent broadcast does not contain user handle: "
                    r2.append(r3)
                    r2.append(r8)
                    java.lang.String r2 = r2.toString()
                    android.util.Log.w(r1, r2)
                    return
                L21:
                    java.lang.String r1 = "android.intent.extra.REPLACING"
                    r2 = 0
                    boolean r1 = r8.getBooleanExtra(r1, r2)
                    boolean r3 = com.android.server.media.MediaSessionService.DEBUG
                    if (r3 == 0) goto L42
                    java.lang.String r3 = "MediaSessionService"
                    java.lang.StringBuilder r4 = new java.lang.StringBuilder
                    r4.<init>()
                    java.lang.String r5 = "Received change in packages, intent="
                    r4.append(r5)
                    r4.append(r8)
                    java.lang.String r4 = r4.toString()
                    android.util.Log.d(r3, r4)
                L42:
                    java.lang.String r3 = r8.getAction()
                    r4 = -1
                    int r5 = r3.hashCode()
                    switch(r5) {
                        case -1403934493: goto L94;
                        case -1338021860: goto L8a;
                        case -1001645458: goto L80;
                        case -810471698: goto L76;
                        case 172491798: goto L6c;
                        case 525384130: goto L62;
                        case 1290767157: goto L58;
                        case 1544582882: goto L4f;
                        default: goto L4e;
                    }
                L4e:
                    goto L9e
                L4f:
                    java.lang.String r5 = "android.intent.action.PACKAGE_ADDED"
                    boolean r3 = r3.equals(r5)
                    if (r3 == 0) goto L9e
                    goto L9f
                L58:
                    java.lang.String r2 = "android.intent.action.PACKAGES_UNSUSPENDED"
                    boolean r2 = r3.equals(r2)
                    if (r2 == 0) goto L9e
                    r2 = 6
                    goto L9f
                L62:
                    java.lang.String r2 = "android.intent.action.PACKAGE_REMOVED"
                    boolean r2 = r3.equals(r2)
                    if (r2 == 0) goto L9e
                    r2 = 1
                    goto L9f
                L6c:
                    java.lang.String r2 = "android.intent.action.PACKAGE_CHANGED"
                    boolean r2 = r3.equals(r2)
                    if (r2 == 0) goto L9e
                    r2 = 4
                    goto L9f
                L76:
                    java.lang.String r2 = "android.intent.action.PACKAGE_REPLACED"
                    boolean r2 = r3.equals(r2)
                    if (r2 == 0) goto L9e
                    r2 = 7
                    goto L9f
                L80:
                    java.lang.String r2 = "android.intent.action.PACKAGES_SUSPENDED"
                    boolean r2 = r3.equals(r2)
                    if (r2 == 0) goto L9e
                    r2 = 5
                    goto L9f
                L8a:
                    java.lang.String r2 = "android.intent.action.EXTERNAL_APPLICATIONS_AVAILABLE"
                    boolean r2 = r3.equals(r2)
                    if (r2 == 0) goto L9e
                    r2 = 2
                    goto L9f
                L94:
                    java.lang.String r2 = "android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE"
                    boolean r2 = r3.equals(r2)
                    if (r2 == 0) goto L9e
                    r2 = 3
                    goto L9f
                L9e:
                    r2 = r4
                L9f:
                    switch(r2) {
                        case 0: goto La3;
                        case 1: goto La3;
                        case 2: goto La3;
                        case 3: goto La3;
                        case 4: goto La6;
                        case 5: goto La6;
                        case 6: goto La6;
                        case 7: goto La6;
                        default: goto La2;
                    }
                La2:
                    goto Lab
                La3:
                    if (r1 == 0) goto La6
                    goto Lab
                La6:
                    com.android.server.media.MediaSessionService r2 = com.android.server.media.MediaSessionService.this
                    com.android.server.media.MediaSessionService.access$1000(r2)
                Lab:
                    return
                */
                throw new UnsupportedOperationException("Method not decompiled: com.android.server.media.MediaSessionService.AnonymousClass1.onReceive(android.content.Context, android.content.Intent):void");
            }
        }, UserHandle.ALL, filter, null, BackgroundThread.getHandler());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void buildMediaSessionService2List() {
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enforcePackageName(String packageName, int uid) {
        if (TextUtils.isEmpty(packageName)) {
            throw new IllegalArgumentException("packageName may not be empty");
        }
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        for (String str : packages) {
            if (packageName.equals(str)) {
                return;
            }
        }
        throw new IllegalArgumentException("packageName is not owned by the calling process");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enforceMediaPermissions(ComponentName compName, int pid, int uid, int resolvedUserId) {
        if (!isCurrentVolumeController(pid, uid) && getContext().checkPermission("android.permission.MEDIA_CONTENT_CONTROL", pid, uid) != 0 && !isEnabledNotificationListener(compName, UserHandle.getUserId(uid), resolvedUserId)) {
            throw new SecurityException("Missing permission to control media.");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isCurrentVolumeController(int pid, int uid) {
        return getContext().checkPermission("android.permission.STATUS_BAR_SERVICE", pid, uid) == 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enforceSystemUiPermission(String action, int pid, int uid) {
        if (!isCurrentVolumeController(pid, uid)) {
            throw new SecurityException("Only system ui may " + action);
        }
    }

    private boolean isEnabledNotificationListener(ComponentName compName, int userId, int forUserId) {
        if (userId != forUserId) {
            return false;
        }
        if (DEBUG) {
            Log.d(TAG, "Checking if enabled notification listener " + compName);
        }
        if (compName != null) {
            try {
                return this.mNotificationManager.isNotificationListenerAccessGrantedForUser(compName, userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Dead NotificationManager in isEnabledNotificationListener", e);
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public MediaSessionRecord createSessionInternal(int callerPid, int callerUid, int userId, String callerPackageName, ISessionCallback cb, String tag) throws RemoteException {
        MediaSessionRecord createSessionLocked;
        synchronized (this.mLock) {
            createSessionLocked = createSessionLocked(callerPid, callerUid, userId, callerPackageName, cb, tag);
        }
        return createSessionLocked;
    }

    private MediaSessionRecord createSessionLocked(int callerPid, int callerUid, int userId, String callerPackageName, ISessionCallback cb, String tag) {
        FullUserRecord user = getFullUserRecordLocked(userId);
        if (user == null) {
            Log.wtf(TAG, "Request from invalid user: " + userId);
            throw new RuntimeException("Session request from invalid user.");
        }
        MediaSessionRecord session = new MediaSessionRecord(callerPid, callerUid, userId, callerPackageName, cb, tag, this, this.mHandler.getLooper());
        try {
            cb.asBinder().linkToDeath(session, 0);
            user.mPriorityStack.addSession(session);
            this.mHandler.postSessionsChanged(userId);
            if (DEBUG) {
                Log.d(TAG, "Created session for " + callerPackageName + " with tag " + tag);
            }
            return session;
        } catch (RemoteException e) {
            throw new RuntimeException("Media Session owner died prematurely.", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int findIndexOfSessionsListenerLocked(IActiveSessionsListener listener) {
        for (int i = this.mSessionsListeners.size() - 1; i >= 0; i--) {
            if (this.mSessionsListeners.get(i).mListener.asBinder() == listener.asBinder()) {
                return i;
            }
        }
        return -1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pushSessionsChanged(int userId) {
        synchronized (this.mLock) {
            FullUserRecord user = getFullUserRecordLocked(userId);
            if (user == null) {
                Log.w(TAG, "pushSessionsChanged failed. No user with id=" + userId);
                return;
            }
            List<MediaSessionRecord> records = getActiveSessionsLocked(userId);
            int size = records.size();
            ArrayList<MediaSession.Token> tokens = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                tokens.add(new MediaSession.Token(records.get(i).getControllerBinder()));
            }
            pushRemoteVolumeUpdateLocked(userId);
            for (int i2 = this.mSessionsListeners.size() - 1; i2 >= 0; i2--) {
                SessionsListenerRecord record = this.mSessionsListeners.get(i2);
                if (record.mUserId == -1 || record.mUserId == userId) {
                    try {
                        record.mListener.onActiveSessionsChanged(tokens);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Dead ActiveSessionsListener in pushSessionsChanged, removing", e);
                        this.mSessionsListeners.remove(i2);
                    }
                }
            }
        }
    }

    private void pushRemoteVolumeUpdateLocked(int userId) {
        if (this.mRvc != null) {
            try {
                FullUserRecord user = getFullUserRecordLocked(userId);
                if (user == null) {
                    Log.w(TAG, "pushRemoteVolumeUpdateLocked failed. No user with id=" + userId);
                    return;
                }
                MediaSessionRecord record = user.mPriorityStack.getDefaultRemoteSession(userId);
                this.mRvc.updateRemoteController(record == null ? null : record.getControllerBinder());
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error sending default remote volume to sys ui.", e);
            }
        }
    }

    public void onMediaButtonReceiverChanged(MediaSessionRecord record) {
        synchronized (this.mLock) {
            FullUserRecord user = getFullUserRecordLocked(record.getUserId());
            MediaSessionRecord mediaButtonSession = user.mPriorityStack.getMediaButtonSession();
            if (record == mediaButtonSession) {
                user.rememberMediaButtonReceiverLocked(mediaButtonSession);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String getCallingPackageName(int uid) {
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages != null && packages.length > 0) {
            return packages[0];
        }
        return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchVolumeKeyLongPressLocked(KeyEvent keyEvent) {
        if (this.mCurrentFullUserRecord.mOnVolumeKeyLongPressListener == null) {
            return;
        }
        try {
            this.mCurrentFullUserRecord.mOnVolumeKeyLongPressListener.onVolumeKeyLongPress(keyEvent);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to send " + keyEvent + " to volume key long-press listener");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public FullUserRecord getFullUserRecordLocked(int userId) {
        int fullUserId = this.mFullUserIds.get(userId, -1);
        if (fullUserId < 0) {
            return null;
        }
        return this.mUserRecords.get(fullUserId);
    }

    void destroySession2Internal(SessionToken2 token) {
        synchronized (this.mLock) {
            boolean notifySessionTokensUpdated = token.getType() == 0 ? false | removeSessionRecordLocked(token) : false | addSessionRecordLocked(token);
            if (notifySessionTokensUpdated) {
                postSessionTokensUpdated(UserHandle.getUserId(token.getUid()));
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class FullUserRecord implements MediaSessionStack.OnMediaButtonSessionChangedListener {
        private static final String COMPONENT_NAME_USER_ID_DELIM = ",";
        private ICallback mCallback;
        private final int mFullUserId;
        private boolean mInitialDownMusicOnly;
        private KeyEvent mInitialDownVolumeKeyEvent;
        private int mInitialDownVolumeStream;
        private PendingIntent mLastMediaButtonReceiver;
        private IOnMediaKeyListener mOnMediaKeyListener;
        private int mOnMediaKeyListenerUid;
        private IOnVolumeKeyLongPressListener mOnVolumeKeyLongPressListener;
        private int mOnVolumeKeyLongPressListenerUid;
        private final MediaSessionStack mPriorityStack;
        private ComponentName mRestoredMediaButtonReceiver;
        private int mRestoredMediaButtonReceiverUserId;
        private ICallback mXuiCallback;

        public FullUserRecord(int fullUserId) {
            String[] tokens;
            this.mFullUserId = fullUserId;
            this.mPriorityStack = new MediaSessionStack(MediaSessionService.this.mAudioPlayerStateMonitor, this);
            String mediaButtonReceiver = Settings.Secure.getStringForUser(MediaSessionService.this.mContentResolver, "media_button_receiver", this.mFullUserId);
            if (mediaButtonReceiver == null || (tokens = mediaButtonReceiver.split(COMPONENT_NAME_USER_ID_DELIM)) == null || tokens.length != 2) {
                return;
            }
            this.mRestoredMediaButtonReceiver = ComponentName.unflattenFromString(tokens[0]);
            this.mRestoredMediaButtonReceiverUserId = Integer.parseInt(tokens[1]);
        }

        public void destroySessionsForUserLocked(int userId) {
            List<MediaSessionRecord> sessions = this.mPriorityStack.getPriorityList(false, userId);
            for (MediaSessionRecord session : sessions) {
                MediaSessionService.this.destroySessionLocked(session);
            }
        }

        public void dumpLocked(PrintWriter pw, String prefix) {
            pw.print(prefix + "Record for full_user=" + this.mFullUserId);
            int size = MediaSessionService.this.mFullUserIds.size();
            for (int i = 0; i < size; i++) {
                if (MediaSessionService.this.mFullUserIds.keyAt(i) != MediaSessionService.this.mFullUserIds.valueAt(i) && MediaSessionService.this.mFullUserIds.valueAt(i) == this.mFullUserId) {
                    pw.print(", profile_user=" + MediaSessionService.this.mFullUserIds.keyAt(i));
                }
            }
            pw.println();
            String indent = prefix + "  ";
            pw.println(indent + "Volume key long-press listener: " + this.mOnVolumeKeyLongPressListener);
            pw.println(indent + "Volume key long-press listener package: " + MediaSessionService.this.getCallingPackageName(this.mOnVolumeKeyLongPressListenerUid));
            pw.println(indent + "Media key listener: " + this.mOnMediaKeyListener);
            pw.println(indent + "Media key listener package: " + MediaSessionService.this.getCallingPackageName(this.mOnMediaKeyListenerUid));
            pw.println(indent + "Callback: " + this.mCallback);
            pw.println(indent + "Last MediaButtonReceiver: " + this.mLastMediaButtonReceiver);
            pw.println(indent + "Restored MediaButtonReceiver: " + this.mRestoredMediaButtonReceiver);
            this.mPriorityStack.dump(pw, indent);
        }

        @Override // com.android.server.media.MediaSessionStack.OnMediaButtonSessionChangedListener
        public void onMediaButtonSessionChanged(MediaSessionRecord oldMediaButtonSession, MediaSessionRecord newMediaButtonSession) {
            Log.d(MediaSessionService.TAG, "Media button session is changed to " + newMediaButtonSession);
            synchronized (MediaSessionService.this.mLock) {
                if (oldMediaButtonSession != null) {
                    try {
                        MediaSessionService.this.mHandler.postSessionsChanged(oldMediaButtonSession.getUserId());
                    } catch (Throwable th) {
                        throw th;
                    }
                }
                if (newMediaButtonSession != null) {
                    rememberMediaButtonReceiverLocked(newMediaButtonSession);
                    MediaSessionService.this.mHandler.postSessionsChanged(newMediaButtonSession.getUserId());
                }
                pushAddressedPlayerChangedLocked();
            }
        }

        public void rememberMediaButtonReceiverLocked(MediaSessionRecord record) {
            ComponentName component;
            PendingIntent receiver = record.getMediaButtonReceiver();
            this.mLastMediaButtonReceiver = receiver;
            this.mRestoredMediaButtonReceiver = null;
            String componentName = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
            if (receiver != null && (component = receiver.getIntent().getComponent()) != null && record.getPackageName().equals(component.getPackageName())) {
                componentName = component.flattenToString();
            }
            ContentResolver contentResolver = MediaSessionService.this.mContentResolver;
            Settings.Secure.putStringForUser(contentResolver, "media_button_receiver", componentName + COMPONENT_NAME_USER_ID_DELIM + record.getUserId(), this.mFullUserId);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void pushAddressedPlayerChangedLocked() {
            if (this.mCallback == null && this.mXuiCallback == null) {
                return;
            }
            try {
                MediaSessionRecord mediaButtonSession = getMediaButtonSessionLocked();
                if (mediaButtonSession == null) {
                    if (MediaSessionService.this.mCurrentFullUserRecord.mLastMediaButtonReceiver == null) {
                        if (MediaSessionService.this.mCurrentFullUserRecord.mRestoredMediaButtonReceiver != null) {
                            if (this.mCallback != null) {
                                this.mCallback.onAddressedPlayerChangedToMediaButtonReceiver(MediaSessionService.this.mCurrentFullUserRecord.mRestoredMediaButtonReceiver);
                            }
                            if (this.mXuiCallback != null) {
                                this.mXuiCallback.onAddressedPlayerChangedToMediaButtonReceiver(MediaSessionService.this.mCurrentFullUserRecord.mRestoredMediaButtonReceiver);
                                return;
                            }
                            return;
                        }
                        return;
                    }
                    if (this.mCallback != null) {
                        this.mCallback.onAddressedPlayerChangedToMediaButtonReceiver(MediaSessionService.this.mCurrentFullUserRecord.mLastMediaButtonReceiver.getIntent().getComponent());
                    }
                    if (this.mXuiCallback != null) {
                        this.mXuiCallback.onAddressedPlayerChangedToMediaButtonReceiver(MediaSessionService.this.mCurrentFullUserRecord.mLastMediaButtonReceiver.getIntent().getComponent());
                        return;
                    }
                    return;
                }
                if (this.mCallback != null) {
                    this.mCallback.onAddressedPlayerChangedToMediaSession(new MediaSession.Token(mediaButtonSession.getControllerBinder()));
                }
                if (this.mXuiCallback != null) {
                    this.mXuiCallback.onAddressedPlayerChangedToMediaSession(new MediaSession.Token(mediaButtonSession.getControllerBinder()));
                }
            } catch (RemoteException e) {
                Log.w(MediaSessionService.TAG, "Failed to pushAddressedPlayerChangedLocked", e);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public MediaSessionRecord getMediaButtonSessionLocked() {
            return MediaSessionService.this.isGlobalPriorityActiveLocked() ? MediaSessionService.this.mGlobalPrioritySession : this.mPriorityStack.getMediaButtonSession();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class SessionsListenerRecord implements IBinder.DeathRecipient {
        private final ComponentName mComponentName;
        private final IActiveSessionsListener mListener;
        private final int mPid;
        private final int mUid;
        private final int mUserId;

        public SessionsListenerRecord(IActiveSessionsListener listener, ComponentName componentName, int userId, int pid, int uid) {
            this.mListener = listener;
            this.mComponentName = componentName;
            this.mUserId = userId;
            this.mPid = pid;
            this.mUid = uid;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            synchronized (MediaSessionService.this.mLock) {
                MediaSessionService.this.mSessionsListeners.remove(this);
            }
        }
    }

    /* loaded from: classes.dex */
    final class SettingsObserver extends ContentObserver {
        private final Uri mSecureSettingsUri;

        private SettingsObserver() {
            super(null);
            this.mSecureSettingsUri = Settings.Secure.getUriFor("enabled_notification_listeners");
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void observe() {
            MediaSessionService.this.mContentResolver.registerContentObserver(this.mSecureSettingsUri, false, this, -1);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            MediaSessionService.this.updateActiveSessionListeners();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class SessionManagerImpl extends ISessionManager.Stub {
        private static final String EXTRA_WAKELOCK_ACQUIRED = "android.media.AudioService.WAKELOCK_ACQUIRED";
        private static final int WAKELOCK_RELEASE_ON_FINISHED = 1980;
        private KeyEventWakeLockReceiver mKeyEventReceiver;
        private boolean mVoiceButtonDown = false;
        private boolean mVoiceButtonHandled = false;
        BroadcastReceiver mKeyEventDone = new BroadcastReceiver() { // from class: com.android.server.media.MediaSessionService.SessionManagerImpl.6
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                Bundle extras;
                if (intent != null && (extras = intent.getExtras()) != null) {
                    synchronized (MediaSessionService.this.mLock) {
                        if (extras.containsKey(SessionManagerImpl.EXTRA_WAKELOCK_ACQUIRED) && MediaSessionService.this.mMediaEventWakeLock.isHeld()) {
                            MediaSessionService.this.mMediaEventWakeLock.release();
                        }
                    }
                }
            }
        };

        SessionManagerImpl() {
            this.mKeyEventReceiver = new KeyEventWakeLockReceiver(MediaSessionService.this.mHandler);
        }

        public ISession createSession(String packageName, ISessionCallback cb, String tag, int userId) throws RemoteException {
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            long token = Binder.clearCallingIdentity();
            try {
                MediaSessionService.this.enforcePackageName(packageName, uid);
                int resolvedUserId = ActivityManager.handleIncomingUser(pid, uid, userId, false, true, "createSession", packageName);
                if (cb == null) {
                    throw new IllegalArgumentException("Controller callback cannot be null");
                }
                return MediaSessionService.this.createSessionInternal(pid, uid, resolvedUserId, packageName, cb, tag).getSessionBinder();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public List<IBinder> getSessions(ComponentName componentName, int userId) {
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            long token = Binder.clearCallingIdentity();
            try {
                int resolvedUserId = verifySessionsRequest(componentName, userId, pid, uid);
                ArrayList<IBinder> binders = new ArrayList<>();
                synchronized (MediaSessionService.this.mLock) {
                    List<MediaSessionRecord> records = MediaSessionService.this.getActiveSessionsLocked(resolvedUserId);
                    for (MediaSessionRecord record : records) {
                        binders.add(record.getControllerBinder().asBinder());
                    }
                }
                return binders;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void addSessionsListener(IActiveSessionsListener listener, ComponentName componentName, int userId) throws RemoteException {
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            long token = Binder.clearCallingIdentity();
            try {
                int resolvedUserId = verifySessionsRequest(componentName, userId, pid, uid);
                synchronized (MediaSessionService.this.mLock) {
                    int index = MediaSessionService.this.findIndexOfSessionsListenerLocked(listener);
                    if (index != -1) {
                        Log.w(MediaSessionService.TAG, "ActiveSessionsListener is already added, ignoring");
                        return;
                    }
                    SessionsListenerRecord record = new SessionsListenerRecord(listener, componentName, resolvedUserId, pid, uid);
                    try {
                        listener.asBinder().linkToDeath(record, 0);
                        MediaSessionService.this.mSessionsListeners.add(record);
                    } catch (RemoteException e) {
                        Log.e(MediaSessionService.TAG, "ActiveSessionsListener is dead, ignoring it", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void removeSessionsListener(IActiveSessionsListener listener) throws RemoteException {
            synchronized (MediaSessionService.this.mLock) {
                int index = MediaSessionService.this.findIndexOfSessionsListenerLocked(listener);
                if (index != -1) {
                    SessionsListenerRecord record = (SessionsListenerRecord) MediaSessionService.this.mSessionsListeners.remove(index);
                    try {
                        record.mListener.asBinder().unlinkToDeath(record, 0);
                    } catch (Exception e) {
                    }
                }
            }
        }

        public void dispatchMediaKeyEvent(String packageName, boolean asSystemService, KeyEvent keyEvent, boolean needWakeLock) {
            StringBuilder sb;
            String str;
            boolean z;
            if (keyEvent == null || !KeyEvent.isMediaKey(keyEvent.getKeyCode())) {
                Log.w(MediaSessionService.TAG, "Attempted to dispatch null or non-media key event.");
                return;
            }
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            long token = Binder.clearCallingIdentity();
            try {
                if (MediaSessionService.DEBUG) {
                    try {
                        sb = new StringBuilder();
                        sb.append("dispatchMediaKeyEvent, pkg=");
                        str = packageName;
                    } catch (Throwable th) {
                        th = th;
                    }
                    try {
                        sb.append(str);
                        sb.append(" pid=");
                        sb.append(pid);
                        sb.append(", uid=");
                        sb.append(uid);
                        sb.append(", asSystem=");
                        z = asSystemService;
                        sb.append(z);
                        sb.append(", event=");
                        sb.append(keyEvent);
                        Log.d(MediaSessionService.TAG, sb.toString());
                    } catch (Throwable th2) {
                        th = th2;
                        Binder.restoreCallingIdentity(token);
                        throw th;
                    }
                } else {
                    str = packageName;
                    z = asSystemService;
                }
                if (!isUserSetupComplete()) {
                    Slog.i(MediaSessionService.TAG, "Not dispatching media key event because user setup is in progress.");
                    Binder.restoreCallingIdentity(token);
                    return;
                }
                synchronized (MediaSessionService.this.mLock) {
                    boolean isGlobalPriorityActive = MediaSessionService.this.isGlobalPriorityActiveLocked();
                    if (isGlobalPriorityActive && uid != 1000) {
                        Slog.i(MediaSessionService.TAG, "Only the system can dispatch media key event to the global priority session.");
                        Binder.restoreCallingIdentity(token);
                        return;
                    }
                    if (!isGlobalPriorityActive && MediaSessionService.this.mCurrentFullUserRecord.mOnMediaKeyListener != null) {
                        Log.d(MediaSessionService.TAG, "Send " + keyEvent + " to the media key listener");
                        try {
                            MediaSessionService.this.mCurrentFullUserRecord.mOnMediaKeyListener.onMediaKey(keyEvent, new MediaKeyListenerResultReceiver(str, pid, uid, z, keyEvent, needWakeLock));
                            Binder.restoreCallingIdentity(token);
                            return;
                        } catch (RemoteException e) {
                            Log.w(MediaSessionService.TAG, "Failed to send " + keyEvent + " to the media key listener");
                        }
                    }
                    if (isGlobalPriorityActive || !isVoiceKey(keyEvent.getKeyCode())) {
                        dispatchMediaKeyEventLocked(packageName, pid, uid, asSystemService, keyEvent, needWakeLock);
                    } else {
                        handleVoiceKeyEventLocked(packageName, pid, uid, asSystemService, keyEvent, needWakeLock);
                    }
                    Binder.restoreCallingIdentity(token);
                }
            } catch (Throwable th3) {
                th = th3;
            }
        }

        public void setCallback(ICallback callback) {
            Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            long token = Binder.clearCallingIdentity();
            try {
                if (!UserHandle.isSameApp(uid, 1002)) {
                    throw new SecurityException("Only Bluetooth service processes can set Callback");
                }
                synchronized (MediaSessionService.this.mLock) {
                    int userId = UserHandle.getUserId(uid);
                    final FullUserRecord user = MediaSessionService.this.getFullUserRecordLocked(userId);
                    if (user != null && user.mFullUserId == userId) {
                        user.mCallback = callback;
                        Log.d(MediaSessionService.TAG, "The callback " + user.mCallback + " is set by " + MediaSessionService.this.getCallingPackageName(uid));
                        if (user.mCallback == null) {
                            return;
                        }
                        try {
                            user.mCallback.asBinder().linkToDeath(new IBinder.DeathRecipient() { // from class: com.android.server.media.MediaSessionService.SessionManagerImpl.1
                                @Override // android.os.IBinder.DeathRecipient
                                public void binderDied() {
                                    synchronized (MediaSessionService.this.mLock) {
                                        user.mCallback = null;
                                    }
                                }
                            }, 0);
                            user.pushAddressedPlayerChangedLocked();
                        } catch (RemoteException e) {
                            Log.w(MediaSessionService.TAG, "Failed to set callback", e);
                            user.mCallback = null;
                        }
                        return;
                    }
                    Log.w(MediaSessionService.TAG, "Only the full user can set the callback, userId=" + userId);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void setOnVolumeKeyLongPressListener(IOnVolumeKeyLongPressListener listener) {
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            long token = Binder.clearCallingIdentity();
            try {
                if (MediaSessionService.this.getContext().checkPermission("android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER", pid, uid) != 0) {
                    throw new SecurityException("Must hold the SET_VOLUME_KEY_LONG_PRESS_LISTENER permission.");
                }
                synchronized (MediaSessionService.this.mLock) {
                    int userId = UserHandle.getUserId(uid);
                    final FullUserRecord user = MediaSessionService.this.getFullUserRecordLocked(userId);
                    if (user != null && user.mFullUserId == userId) {
                        if (user.mOnVolumeKeyLongPressListener != null && user.mOnVolumeKeyLongPressListenerUid != uid) {
                            Log.w(MediaSessionService.TAG, "The volume key long-press listener cannot be reset by another app , mOnVolumeKeyLongPressListener=" + user.mOnVolumeKeyLongPressListenerUid + ", uid=" + uid);
                            return;
                        }
                        user.mOnVolumeKeyLongPressListener = listener;
                        user.mOnVolumeKeyLongPressListenerUid = uid;
                        Log.d(MediaSessionService.TAG, "The volume key long-press listener " + listener + " is set by " + MediaSessionService.this.getCallingPackageName(uid));
                        if (user.mOnVolumeKeyLongPressListener != null) {
                            try {
                                user.mOnVolumeKeyLongPressListener.asBinder().linkToDeath(new IBinder.DeathRecipient() { // from class: com.android.server.media.MediaSessionService.SessionManagerImpl.2
                                    @Override // android.os.IBinder.DeathRecipient
                                    public void binderDied() {
                                        synchronized (MediaSessionService.this.mLock) {
                                            user.mOnVolumeKeyLongPressListener = null;
                                        }
                                    }
                                }, 0);
                            } catch (RemoteException e) {
                                Log.w(MediaSessionService.TAG, "Failed to set death recipient " + user.mOnVolumeKeyLongPressListener);
                                user.mOnVolumeKeyLongPressListener = null;
                            }
                        }
                        return;
                    }
                    Log.w(MediaSessionService.TAG, "Only the full user can set the volume key long-press listener, userId=" + userId);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void setOnMediaKeyListener(IOnMediaKeyListener listener) {
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            long token = Binder.clearCallingIdentity();
            try {
                if (MediaSessionService.this.getContext().checkPermission("android.permission.SET_MEDIA_KEY_LISTENER", pid, uid) != 0) {
                    throw new SecurityException("Must hold the SET_MEDIA_KEY_LISTENER permission.");
                }
                synchronized (MediaSessionService.this.mLock) {
                    int userId = UserHandle.getUserId(uid);
                    final FullUserRecord user = MediaSessionService.this.getFullUserRecordLocked(userId);
                    if (user != null && user.mFullUserId == userId) {
                        if (user.mOnMediaKeyListener != null && user.mOnMediaKeyListenerUid != uid) {
                            Log.w(MediaSessionService.TAG, "The media key listener cannot be reset by another app. , mOnMediaKeyListenerUid=" + user.mOnMediaKeyListenerUid + ", uid=" + uid);
                            return;
                        }
                        user.mOnMediaKeyListener = listener;
                        user.mOnMediaKeyListenerUid = uid;
                        Log.d(MediaSessionService.TAG, "The media key listener " + user.mOnMediaKeyListener + " is set by " + MediaSessionService.this.getCallingPackageName(uid));
                        if (user.mOnMediaKeyListener != null) {
                            try {
                                user.mOnMediaKeyListener.asBinder().linkToDeath(new IBinder.DeathRecipient() { // from class: com.android.server.media.MediaSessionService.SessionManagerImpl.3
                                    @Override // android.os.IBinder.DeathRecipient
                                    public void binderDied() {
                                        synchronized (MediaSessionService.this.mLock) {
                                            user.mOnMediaKeyListener = null;
                                        }
                                    }
                                }, 0);
                            } catch (RemoteException e) {
                                Log.w(MediaSessionService.TAG, "Failed to set death recipient " + user.mOnMediaKeyListener);
                                user.mOnMediaKeyListener = null;
                            }
                        }
                        return;
                    }
                    Log.w(MediaSessionService.TAG, "Only the full user can set the media key listener, userId=" + userId);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void dispatchVolumeKeyEvent(String packageName, boolean asSystemService, KeyEvent keyEvent, int stream, boolean musicOnly) {
            if (keyEvent == null || !(keyEvent.getKeyCode() == 24 || keyEvent.getKeyCode() == 25 || keyEvent.getKeyCode() == 164)) {
                Log.w(MediaSessionService.TAG, "Attempted to dispatch null or non-volume key event.");
                return;
            }
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            long token = Binder.clearCallingIdentity();
            Log.d(MediaSessionService.TAG, "dispatchVolumeKeyEvent, pkg=" + packageName + ", pid=" + pid + ", uid=" + uid + ", asSystem=" + asSystemService + ", event=" + keyEvent);
            try {
                synchronized (MediaSessionService.this.mLock) {
                    try {
                        if (!MediaSessionService.this.isGlobalPriorityActiveLocked() && MediaSessionService.this.mCurrentFullUserRecord.mOnVolumeKeyLongPressListener != null) {
                            if (keyEvent.getAction() == 0) {
                                try {
                                    if (keyEvent.getRepeatCount() == 0) {
                                        MediaSessionService.this.mCurrentFullUserRecord.mInitialDownVolumeKeyEvent = KeyEvent.obtain(keyEvent);
                                        try {
                                            MediaSessionService.this.mCurrentFullUserRecord.mInitialDownVolumeStream = stream;
                                            MediaSessionService.this.mCurrentFullUserRecord.mInitialDownMusicOnly = musicOnly;
                                            MediaSessionService.this.mHandler.sendMessageDelayed(MediaSessionService.this.mHandler.obtainMessage(2, MediaSessionService.this.mCurrentFullUserRecord.mFullUserId, 0), MediaSessionService.this.mLongPressTimeout);
                                        } catch (Throwable th) {
                                            th = th;
                                            throw th;
                                        }
                                    }
                                    if (keyEvent.getRepeatCount() > 0 || keyEvent.isLongPress()) {
                                        MediaSessionService.this.mHandler.removeMessages(2);
                                        if (MediaSessionService.this.mCurrentFullUserRecord.mInitialDownVolumeKeyEvent != null) {
                                            MediaSessionService.this.dispatchVolumeKeyLongPressLocked(MediaSessionService.this.mCurrentFullUserRecord.mInitialDownVolumeKeyEvent);
                                            MediaSessionService.this.mCurrentFullUserRecord.mInitialDownVolumeKeyEvent = null;
                                        }
                                        MediaSessionService.this.dispatchVolumeKeyLongPressLocked(keyEvent);
                                    }
                                } catch (Throwable th2) {
                                    th = th2;
                                }
                            } else {
                                MediaSessionService.this.mHandler.removeMessages(2);
                                if (MediaSessionService.this.mCurrentFullUserRecord.mInitialDownVolumeKeyEvent == null || MediaSessionService.this.mCurrentFullUserRecord.mInitialDownVolumeKeyEvent.getDownTime() != keyEvent.getDownTime()) {
                                    MediaSessionService.this.dispatchVolumeKeyLongPressLocked(keyEvent);
                                } else {
                                    dispatchVolumeKeyEventLocked(packageName, pid, uid, asSystemService, MediaSessionService.this.mCurrentFullUserRecord.mInitialDownVolumeKeyEvent, MediaSessionService.this.mCurrentFullUserRecord.mInitialDownVolumeStream, MediaSessionService.this.mCurrentFullUserRecord.mInitialDownMusicOnly);
                                    dispatchVolumeKeyEventLocked(packageName, pid, uid, asSystemService, keyEvent, stream, musicOnly);
                                }
                            }
                        }
                        dispatchVolumeKeyEventLocked(packageName, pid, uid, asSystemService, keyEvent, stream, musicOnly);
                    } catch (Throwable th3) {
                        th = th3;
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void setXuiCallback(ICallback callback) {
            Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (MediaSessionService.this.mLock) {
                    int userId = UserHandle.getUserId(uid);
                    final FullUserRecord user = MediaSessionService.this.getFullUserRecordLocked(userId);
                    if (user != null && user.mFullUserId == userId) {
                        user.mXuiCallback = callback;
                        Log.d(MediaSessionService.TAG, "The xui callback " + user.mCallback + " is set by " + MediaSessionService.this.getCallingPackageName(uid));
                        if (user.mXuiCallback == null) {
                            return;
                        }
                        try {
                            user.mXuiCallback.asBinder().linkToDeath(new IBinder.DeathRecipient() { // from class: com.android.server.media.MediaSessionService.SessionManagerImpl.4
                                @Override // android.os.IBinder.DeathRecipient
                                public void binderDied() {
                                    synchronized (MediaSessionService.this.mLock) {
                                        user.mXuiCallback = null;
                                    }
                                }
                            }, 0);
                            user.pushAddressedPlayerChangedLocked();
                        } catch (RemoteException e) {
                            Log.w(MediaSessionService.TAG, "Failed to set xui callback", e);
                            user.mXuiCallback = null;
                        }
                        return;
                    }
                    Log.w(MediaSessionService.TAG, "Only the full user can set the xuicallback, userId=" + userId);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private void dispatchVolumeKeyEventLocked(String packageName, int pid, int uid, boolean asSystemService, KeyEvent keyEvent, int stream, boolean musicOnly) {
            int flags;
            boolean down = keyEvent.getAction() == 0;
            boolean up = keyEvent.getAction() == 1;
            int direction = 0;
            boolean isMute = false;
            int keyCode = keyEvent.getKeyCode();
            if (keyCode != 164) {
                switch (keyCode) {
                    case 24:
                        direction = 1;
                        break;
                    case WindowManagerService.H.SHOW_STRICT_MODE_VIOLATION /* 25 */:
                        direction = -1;
                        break;
                }
            } else {
                isMute = true;
            }
            if (down || up) {
                if (musicOnly) {
                    flags = 4096 | 512;
                } else if (up) {
                    flags = 4096 | 20;
                } else {
                    flags = 4096 | 17;
                }
                if (direction != 0) {
                    if (up) {
                        direction = 0;
                    }
                    dispatchAdjustVolumeLocked(packageName, pid, uid, asSystemService, stream, direction, flags);
                } else if (isMute && down && keyEvent.getRepeatCount() == 0) {
                    dispatchAdjustVolumeLocked(packageName, pid, uid, asSystemService, stream, 101, flags);
                }
            }
        }

        public void dispatchAdjustVolume(String packageName, int suggestedStream, int delta, int flags) {
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (MediaSessionService.this.mLock) {
                    dispatchAdjustVolumeLocked(packageName, pid, uid, false, suggestedStream, delta, flags);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void setRemoteVolumeController(IRemoteVolumeController rvc) {
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            long token = Binder.clearCallingIdentity();
            try {
                MediaSessionService.this.enforceSystemUiPermission("listen for volume changes", pid, uid);
                MediaSessionService.this.mRvc = rvc;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public boolean isGlobalPriorityActive() {
            boolean isGlobalPriorityActiveLocked;
            synchronized (MediaSessionService.this.mLock) {
                isGlobalPriorityActiveLocked = MediaSessionService.this.isGlobalPriorityActiveLocked();
            }
            return isGlobalPriorityActiveLocked;
        }

        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (DumpUtils.checkDumpPermission(MediaSessionService.this.getContext(), MediaSessionService.TAG, pw)) {
                pw.println("MEDIA SESSION SERVICE (dumpsys media_session)");
                pw.println();
                synchronized (MediaSessionService.this.mLock) {
                    pw.println(MediaSessionService.this.mSessionsListeners.size() + " sessions listeners.");
                    pw.println("Global priority session is " + MediaSessionService.this.mGlobalPrioritySession);
                    if (MediaSessionService.this.mGlobalPrioritySession != null) {
                        MediaSessionService.this.mGlobalPrioritySession.dump(pw, "  ");
                    }
                    pw.println("User Records:");
                    int count = MediaSessionService.this.mUserRecords.size();
                    for (int i = 0; i < count; i++) {
                        ((FullUserRecord) MediaSessionService.this.mUserRecords.valueAt(i)).dumpLocked(pw, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                    }
                    MediaSessionService.this.mAudioPlayerStateMonitor.dump(MediaSessionService.this.getContext(), pw, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                }
            }
        }

        public boolean isTrusted(String controllerPackageName, int controllerPid, int controllerUid) throws RemoteException {
            int uid = Binder.getCallingUid();
            long token = Binder.clearCallingIdentity();
            try {
                int controllerUserId = UserHandle.getUserId(controllerUid);
                int controllerUidFromPackageName = MediaSessionService.this.getContext().getPackageManager().getPackageUidAsUser(controllerPackageName, controllerUserId);
                if (controllerUidFromPackageName != controllerUid) {
                    if (MediaSessionService.DEBUG) {
                        Log.d(MediaSessionService.TAG, "Package name " + controllerPackageName + " doesn't match with the uid " + controllerUid);
                    }
                    return false;
                }
                return hasMediaControlPermission(UserHandle.getUserId(uid), controllerPackageName, controllerPid, controllerUid);
            } catch (PackageManager.NameNotFoundException e) {
                if (MediaSessionService.DEBUG) {
                    Log.d(MediaSessionService.TAG, "Package " + controllerPackageName + " doesn't exist");
                }
                return false;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public boolean createSession2(Bundle sessionToken) {
            return false;
        }

        public void destroySession2(Bundle sessionToken) {
        }

        public List<Bundle> getSessionTokens(boolean activeSessionOnly, boolean sessionServiceOnly, String packageName) throws RemoteException {
            return null;
        }

        public void addSessionTokensListener(ISessionTokensListener listener, int userId, String packageName) throws RemoteException {
        }

        public void removeSessionTokensListener(ISessionTokensListener listener, String packageName) throws RemoteException {
        }

        private int verifySessionsRequest(ComponentName componentName, int userId, int pid, int uid) {
            String packageName = null;
            if (componentName != null) {
                packageName = componentName.getPackageName();
                MediaSessionService.this.enforcePackageName(packageName, uid);
            }
            int resolvedUserId = ActivityManager.handleIncomingUser(pid, uid, userId, true, true, "getSessions", packageName);
            MediaSessionService.this.enforceMediaPermissions(componentName, pid, uid, resolvedUserId);
            return resolvedUserId;
        }

        private int verifySessionsRequest2(int targetUserId, String callerPackageName, int callerPid, int callerUid) throws RemoteException {
            int resolvedUserId = ActivityManager.handleIncomingUser(callerPid, callerUid, targetUserId, true, true, "getSessionTokens", callerPackageName);
            if (!hasMediaControlPermission(resolvedUserId, callerPackageName, callerPid, callerUid)) {
                throw new SecurityException("Missing permission to control media.");
            }
            return resolvedUserId;
        }

        private boolean hasMediaControlPermission(int resolvedUserId, String packageName, int pid, int uid) throws RemoteException {
            if (MediaSessionService.this.isCurrentVolumeController(pid, uid) || uid == 1000 || MediaSessionService.this.getContext().checkPermission("android.permission.MEDIA_CONTENT_CONTROL", pid, uid) == 0) {
                return true;
            }
            if (MediaSessionService.DEBUG) {
                Log.d(MediaSessionService.TAG, packageName + " (uid=" + uid + ") hasn't granted MEDIA_CONTENT_CONTROL");
            }
            int userId = UserHandle.getUserId(uid);
            if (resolvedUserId == userId) {
                List<ComponentName> enabledNotificationListeners = MediaSessionService.this.mNotificationManager.getEnabledNotificationListeners(userId);
                if (enabledNotificationListeners != null) {
                    for (int i = 0; i < enabledNotificationListeners.size(); i++) {
                        if (TextUtils.equals(packageName, enabledNotificationListeners.get(i).getPackageName())) {
                            return true;
                        }
                    }
                }
                if (MediaSessionService.DEBUG) {
                    Log.d(MediaSessionService.TAG, packageName + " (uid=" + uid + ") doesn't have an enabled notification listener");
                }
                return false;
            }
            return false;
        }

        private void dispatchAdjustVolumeLocked(String packageName, int pid, int uid, boolean asSystemService, final int suggestedStream, final int direction, final int flags) {
            MediaSessionRecord defaultVolumeSession;
            if (MediaSessionService.this.isGlobalPriorityActiveLocked()) {
                defaultVolumeSession = MediaSessionService.this.mGlobalPrioritySession;
            } else {
                defaultVolumeSession = MediaSessionService.this.mCurrentFullUserRecord.mPriorityStack.getDefaultVolumeSession();
            }
            MediaSessionRecord session = defaultVolumeSession;
            boolean preferSuggestedStream = false;
            if (isValidLocalStreamType(suggestedStream) && AudioSystem.isStreamActive(suggestedStream, 0)) {
                preferSuggestedStream = true;
            }
            boolean preferSuggestedStream2 = preferSuggestedStream;
            Log.d(MediaSessionService.TAG, "Adjusting " + session + " by " + direction + ". flags=" + flags + ", suggestedStream=" + suggestedStream + ", preferSuggestedStream=" + preferSuggestedStream2);
            if (session != null && !preferSuggestedStream2) {
                session.adjustVolume(packageName, pid, uid, null, asSystemService, direction, flags, true);
            } else if ((flags & 512) == 0 || AudioSystem.isStreamActive(3, 0)) {
                MediaSessionService.this.mHandler.post(new Runnable() { // from class: com.android.server.media.MediaSessionService.SessionManagerImpl.5
                    @Override // java.lang.Runnable
                    public void run() {
                        try {
                            String packageName2 = MediaSessionService.this.getContext().getOpPackageName();
                            MediaSessionService.this.mAudioService.adjustSuggestedStreamVolume(direction, suggestedStream, flags, packageName2, MediaSessionService.TAG);
                        } catch (RemoteException e) {
                            Log.e(MediaSessionService.TAG, "Error adjusting default volume.", e);
                        } catch (IllegalArgumentException e2) {
                            Log.e(MediaSessionService.TAG, "Cannot adjust volume: direction=" + direction + ", suggestedStream=" + suggestedStream + ", flags=" + flags, e2);
                        }
                    }
                });
            } else if (MediaSessionService.DEBUG) {
                Log.d(MediaSessionService.TAG, "No active session to adjust, skipping media only volume event");
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handleVoiceKeyEventLocked(String packageName, int pid, int uid, boolean asSystemService, KeyEvent keyEvent, boolean needWakeLock) {
            int action = keyEvent.getAction();
            boolean isLongPress = (keyEvent.getFlags() & 128) != 0;
            if (action == 0) {
                if (keyEvent.getRepeatCount() == 0) {
                    this.mVoiceButtonDown = true;
                    this.mVoiceButtonHandled = false;
                } else if (this.mVoiceButtonDown && !this.mVoiceButtonHandled && isLongPress) {
                    this.mVoiceButtonHandled = true;
                    startVoiceInput(needWakeLock);
                }
            } else if (action == 1 && this.mVoiceButtonDown) {
                this.mVoiceButtonDown = false;
                if (!this.mVoiceButtonHandled && !keyEvent.isCanceled()) {
                    KeyEvent downEvent = KeyEvent.changeAction(keyEvent, 0);
                    dispatchMediaKeyEventLocked(packageName, pid, uid, asSystemService, downEvent, needWakeLock);
                    dispatchMediaKeyEventLocked(packageName, pid, uid, asSystemService, keyEvent, needWakeLock);
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void dispatchMediaKeyEventLocked(String packageName, int pid, int uid, boolean asSystemService, KeyEvent keyEvent, boolean needWakeLock) {
            ComponentName componentName;
            MediaSessionRecord session = MediaSessionService.this.mCurrentFullUserRecord.getMediaButtonSessionLocked();
            int i = -1;
            if (session == null) {
                if (MediaSessionService.this.mCurrentFullUserRecord.mLastMediaButtonReceiver != null || MediaSessionService.this.mCurrentFullUserRecord.mRestoredMediaButtonReceiver != null) {
                    if (needWakeLock) {
                        this.mKeyEventReceiver.aquireWakeLockLocked();
                    }
                    Intent mediaButtonIntent = new Intent("android.intent.action.MEDIA_BUTTON");
                    mediaButtonIntent.addFlags(268435456);
                    mediaButtonIntent.putExtra("android.intent.extra.KEY_EVENT", keyEvent);
                    String callerPackageName = asSystemService ? MediaSessionService.this.getContext().getPackageName() : packageName;
                    mediaButtonIntent.putExtra("android.intent.extra.PACKAGE_NAME", callerPackageName);
                    try {
                        if (MediaSessionService.this.mCurrentFullUserRecord.mLastMediaButtonReceiver != null) {
                            PendingIntent receiver = MediaSessionService.this.mCurrentFullUserRecord.mLastMediaButtonReceiver;
                            Log.d(MediaSessionService.TAG, "Sending " + keyEvent + " to the last known PendingIntent " + receiver);
                            Context context = MediaSessionService.this.getContext();
                            if (needWakeLock) {
                                i = this.mKeyEventReceiver.mLastTimeoutId;
                            }
                            receiver.send(context, i, mediaButtonIntent, this.mKeyEventReceiver, MediaSessionService.this.mHandler);
                            if (MediaSessionService.this.mCurrentFullUserRecord.mCallback != null && (componentName = MediaSessionService.this.mCurrentFullUserRecord.mLastMediaButtonReceiver.getIntent().getComponent()) != null) {
                                MediaSessionService.this.mCurrentFullUserRecord.mCallback.onMediaKeyEventDispatchedToMediaButtonReceiver(keyEvent, componentName);
                            }
                            return;
                        }
                        ComponentName receiver2 = MediaSessionService.this.mCurrentFullUserRecord.mRestoredMediaButtonReceiver;
                        Log.d(MediaSessionService.TAG, "Sending " + keyEvent + " to the restored intent " + receiver2);
                        mediaButtonIntent.setComponent(receiver2);
                        MediaSessionService.this.getContext().sendBroadcastAsUser(mediaButtonIntent, UserHandle.of(MediaSessionService.this.mCurrentFullUserRecord.mRestoredMediaButtonReceiverUserId));
                        if (MediaSessionService.this.mCurrentFullUserRecord.mCallback != null) {
                            MediaSessionService.this.mCurrentFullUserRecord.mCallback.onMediaKeyEventDispatchedToMediaButtonReceiver(keyEvent, receiver2);
                            return;
                        }
                        return;
                    } catch (PendingIntent.CanceledException e) {
                        Log.i(MediaSessionService.TAG, "Error sending key event to media button receiver " + MediaSessionService.this.mCurrentFullUserRecord.mLastMediaButtonReceiver, e);
                        return;
                    } catch (RemoteException e2) {
                        Log.w(MediaSessionService.TAG, "Failed to send callback", e2);
                        return;
                    }
                }
                return;
            }
            Log.d(MediaSessionService.TAG, "Sending " + keyEvent + " to " + session);
            if (needWakeLock) {
                this.mKeyEventReceiver.aquireWakeLockLocked();
            }
            if (needWakeLock) {
                i = this.mKeyEventReceiver.mLastTimeoutId;
            }
            session.sendMediaButton(packageName, pid, uid, asSystemService, keyEvent, i, this.mKeyEventReceiver);
            if (MediaSessionService.this.mCurrentFullUserRecord.mCallback != null) {
                try {
                    MediaSessionService.this.mCurrentFullUserRecord.mCallback.onMediaKeyEventDispatchedToMediaSession(keyEvent, new MediaSession.Token(session.getControllerBinder()));
                } catch (RemoteException e3) {
                    Log.w(MediaSessionService.TAG, "Failed to send callback", e3);
                }
            }
        }

        private void startVoiceInput(boolean needWakeLock) {
            Intent voiceIntent;
            PowerManager pm = (PowerManager) MediaSessionService.this.getContext().getSystemService("power");
            boolean z = false;
            boolean isLocked = MediaSessionService.this.mKeyguardManager != null && MediaSessionService.this.mKeyguardManager.isKeyguardLocked();
            if (!isLocked && pm.isScreenOn()) {
                voiceIntent = new Intent("android.speech.action.WEB_SEARCH");
                Log.i(MediaSessionService.TAG, "voice-based interactions: about to use ACTION_WEB_SEARCH");
            } else {
                voiceIntent = new Intent("android.speech.action.VOICE_SEARCH_HANDS_FREE");
                if (isLocked && MediaSessionService.this.mKeyguardManager.isKeyguardSecure()) {
                    z = true;
                }
                voiceIntent.putExtra("android.speech.extras.EXTRA_SECURE", z);
                Log.i(MediaSessionService.TAG, "voice-based interactions: about to use ACTION_VOICE_SEARCH_HANDS_FREE");
            }
            if (needWakeLock) {
                MediaSessionService.this.mMediaEventWakeLock.acquire();
            }
            try {
                try {
                    voiceIntent.setFlags(276824064);
                    if (MediaSessionService.DEBUG) {
                        Log.d(MediaSessionService.TAG, "voiceIntent: " + voiceIntent);
                    }
                    MediaSessionService.this.getContext().startActivityAsUser(voiceIntent, UserHandle.CURRENT);
                    if (!needWakeLock) {
                        return;
                    }
                } catch (ActivityNotFoundException e) {
                    Log.w(MediaSessionService.TAG, "No activity for search: " + e);
                    if (!needWakeLock) {
                        return;
                    }
                }
                MediaSessionService.this.mMediaEventWakeLock.release();
            } catch (Throwable th) {
                if (needWakeLock) {
                    MediaSessionService.this.mMediaEventWakeLock.release();
                }
                throw th;
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean isVoiceKey(int keyCode) {
            return keyCode == 79 || (!MediaSessionService.this.mHasFeatureLeanback && keyCode == 85);
        }

        private boolean isUserSetupComplete() {
            return Settings.Secure.getIntForUser(MediaSessionService.this.getContext().getContentResolver(), "user_setup_complete", 0, -2) != 0;
        }

        private boolean isValidLocalStreamType(int streamType) {
            return streamType >= 0 && streamType <= 5;
        }

        /* loaded from: classes.dex */
        private class MediaKeyListenerResultReceiver extends ResultReceiver implements Runnable {
            private final boolean mAsSystemService;
            private boolean mHandled;
            private final KeyEvent mKeyEvent;
            private final boolean mNeedWakeLock;
            private final String mPackageName;
            private final int mPid;
            private final int mUid;

            private MediaKeyListenerResultReceiver(String packageName, int pid, int uid, boolean asSystemService, KeyEvent keyEvent, boolean needWakeLock) {
                super(MediaSessionService.this.mHandler);
                MediaSessionService.this.mHandler.postDelayed(this, 1000L);
                this.mPackageName = packageName;
                this.mPid = pid;
                this.mUid = uid;
                this.mAsSystemService = asSystemService;
                this.mKeyEvent = keyEvent;
                this.mNeedWakeLock = needWakeLock;
            }

            @Override // java.lang.Runnable
            public void run() {
                Log.d(MediaSessionService.TAG, "The media key listener is timed-out for " + this.mKeyEvent);
                dispatchMediaKeyEvent();
            }

            @Override // android.os.ResultReceiver
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                if (resultCode == 1) {
                    this.mHandled = true;
                    MediaSessionService.this.mHandler.removeCallbacks(this);
                    return;
                }
                dispatchMediaKeyEvent();
            }

            private void dispatchMediaKeyEvent() {
                if (this.mHandled) {
                    return;
                }
                this.mHandled = true;
                MediaSessionService.this.mHandler.removeCallbacks(this);
                synchronized (MediaSessionService.this.mLock) {
                    if (MediaSessionService.this.isGlobalPriorityActiveLocked() || !SessionManagerImpl.this.isVoiceKey(this.mKeyEvent.getKeyCode())) {
                        SessionManagerImpl.this.dispatchMediaKeyEventLocked(this.mPackageName, this.mPid, this.mUid, this.mAsSystemService, this.mKeyEvent, this.mNeedWakeLock);
                    } else {
                        SessionManagerImpl.this.handleVoiceKeyEventLocked(this.mPackageName, this.mPid, this.mUid, this.mAsSystemService, this.mKeyEvent, this.mNeedWakeLock);
                    }
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        /* loaded from: classes.dex */
        public class KeyEventWakeLockReceiver extends ResultReceiver implements Runnable, PendingIntent.OnFinished {
            private final Handler mHandler;
            private int mLastTimeoutId;
            private int mRefCount;

            public KeyEventWakeLockReceiver(Handler handler) {
                super(handler);
                this.mRefCount = 0;
                this.mLastTimeoutId = 0;
                this.mHandler = handler;
            }

            public void onTimeout() {
                synchronized (MediaSessionService.this.mLock) {
                    if (this.mRefCount == 0) {
                        return;
                    }
                    this.mLastTimeoutId++;
                    this.mRefCount = 0;
                    releaseWakeLockLocked();
                }
            }

            public void aquireWakeLockLocked() {
                if (this.mRefCount == 0) {
                    MediaSessionService.this.mMediaEventWakeLock.acquire();
                }
                this.mRefCount++;
                this.mHandler.removeCallbacks(this);
                this.mHandler.postDelayed(this, 5000L);
            }

            @Override // java.lang.Runnable
            public void run() {
                onTimeout();
            }

            @Override // android.os.ResultReceiver
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                if (resultCode >= this.mLastTimeoutId) {
                    synchronized (MediaSessionService.this.mLock) {
                        if (this.mRefCount > 0) {
                            this.mRefCount--;
                            if (this.mRefCount == 0) {
                                releaseWakeLockLocked();
                            }
                        }
                    }
                }
            }

            private void releaseWakeLockLocked() {
                MediaSessionService.this.mMediaEventWakeLock.release();
                this.mHandler.removeCallbacks(this);
            }

            @Override // android.app.PendingIntent.OnFinished
            public void onSendFinished(PendingIntent pendingIntent, Intent intent, int resultCode, String resultData, Bundle resultExtras) {
                onReceiveResult(resultCode, null);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class MessageHandler extends Handler {
        private static final int MSG_SESSIONS_CHANGED = 1;
        private static final int MSG_SESSIONS_TOKENS_CHANGED = 3;
        private static final int MSG_VOLUME_INITIAL_DOWN = 2;
        private final SparseArray<Integer> mIntegerCache = new SparseArray<>();

        MessageHandler() {
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    MediaSessionService.this.pushSessionsChanged(((Integer) msg.obj).intValue());
                    return;
                case 2:
                    synchronized (MediaSessionService.this.mLock) {
                        FullUserRecord user = (FullUserRecord) MediaSessionService.this.mUserRecords.get(msg.arg1);
                        if (user != null && user.mInitialDownVolumeKeyEvent != null) {
                            MediaSessionService.this.dispatchVolumeKeyLongPressLocked(user.mInitialDownVolumeKeyEvent);
                            user.mInitialDownVolumeKeyEvent = null;
                        }
                    }
                    return;
                case 3:
                    MediaSessionService.this.pushSessionTokensChanged(((Integer) msg.obj).intValue());
                    return;
                default:
                    return;
            }
        }

        public void postSessionsChanged(int userId) {
            Integer userIdInteger = this.mIntegerCache.get(userId);
            if (userIdInteger == null) {
                userIdInteger = Integer.valueOf(userId);
                this.mIntegerCache.put(userId, userIdInteger);
            }
            removeMessages(1, userIdInteger);
            obtainMessage(1, userIdInteger).sendToTarget();
        }
    }

    /* loaded from: classes.dex */
    private class ControllerCallback extends MediaController2.ControllerCallback {
        private final SessionToken2 mToken;

        ControllerCallback(SessionToken2 token) {
            this.mToken = token;
        }

        @Override // android.media.MediaController2.ControllerCallback
        public void onDisconnected(MediaController2 controller) {
            MediaSessionService.this.destroySession2Internal(this.mToken);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class SessionTokensListenerRecord implements IBinder.DeathRecipient {
        private final ISessionTokensListener mListener;
        private final int mUserId;

        public SessionTokensListenerRecord(ISessionTokensListener listener, int userId) {
            this.mListener = listener;
            this.mUserId = userId;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            synchronized (MediaSessionService.this.mLock) {
                MediaSessionService.this.mSessionTokensListeners.remove(this);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void postSessionTokensUpdated(int userId) {
        this.mHandler.obtainMessage(3, Integer.valueOf(userId)).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pushSessionTokensChanged(int userId) {
        synchronized (this.mLock) {
            List<Bundle> tokens = new ArrayList<>();
            for (SessionToken2 token : this.mSessionRecords.keySet()) {
                if (UserHandle.getUserId(token.getUid()) == userId || -1 == userId) {
                    tokens.add(token.toBundle());
                }
            }
            for (SessionTokensListenerRecord record : this.mSessionTokensListeners) {
                if (record.mUserId == userId || record.mUserId == -1) {
                    try {
                        record.mListener.onSessionTokensChanged(tokens);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to notify session tokens changed", e);
                    }
                }
            }
        }
    }

    private boolean addSessionRecordLocked(SessionToken2 token) {
        return addSessionRecordLocked(token, null);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean addSessionRecordLocked(SessionToken2 token, MediaController2 controller) {
        if (this.mSessionRecords.containsKey(token) && this.mSessionRecords.get(token) == controller) {
            return false;
        }
        this.mSessionRecords.put(token, controller);
        return true;
    }

    private boolean removeSessionRecordLocked(SessionToken2 token) {
        if (!this.mSessionRecords.containsKey(token)) {
            return false;
        }
        this.mSessionRecords.remove(token);
        return true;
    }
}
