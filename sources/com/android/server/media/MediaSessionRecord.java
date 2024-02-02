package com.android.server.media;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.AudioSystem;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.ISession;
import android.media.session.ISessionCallback;
import android.media.session.ISessionController;
import android.media.session.ISessionControllerCallback;
import android.media.session.MediaSession;
import android.media.session.ParcelableVolumeInfo;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Log;
import android.util.Slog;
import android.view.KeyEvent;
import com.android.server.LocalServices;
import com.android.server.slice.SliceClientPermissions;
import java.io.PrintWriter;
import java.util.ArrayList;
/* loaded from: classes.dex */
public class MediaSessionRecord implements IBinder.DeathRecipient {
    private static final int OPTIMISTIC_VOLUME_TIMEOUT = 1000;
    private AudioManager mAudioManager;
    private final Context mContext;
    private Bundle mExtras;
    private long mFlags;
    private final MessageHandler mHandler;
    private PendingIntent mLaunchIntent;
    private PendingIntent mMediaButtonReceiver;
    private MediaMetadata mMetadata;
    private final int mOwnerPid;
    private final int mOwnerUid;
    private final String mPackageName;
    private PlaybackState mPlaybackState;
    private ParceledListSlice mQueue;
    private CharSequence mQueueTitle;
    private int mRatingType;
    private final MediaSessionService mService;
    private final SessionCb mSessionCb;
    private final String mTag;
    private final int mUserId;
    private static final String TAG = "MediaSessionRecord";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);
    private final Object mLock = new Object();
    private final ArrayList<ISessionControllerCallbackHolder> mControllerCallbackHolders = new ArrayList<>();
    private int mVolumeType = 1;
    private int mVolumeControlType = 2;
    private int mMaxVolume = 0;
    private int mCurrentVolume = 0;
    private int mOptimisticVolume = -1;
    private boolean mIsActive = false;
    private boolean mDestroyed = false;
    private final Runnable mClearOptimisticVolumeRunnable = new Runnable() { // from class: com.android.server.media.MediaSessionRecord.2
        @Override // java.lang.Runnable
        public void run() {
            boolean needUpdate = MediaSessionRecord.this.mOptimisticVolume != MediaSessionRecord.this.mCurrentVolume;
            MediaSessionRecord.this.mOptimisticVolume = -1;
            if (needUpdate) {
                MediaSessionRecord.this.pushVolumeUpdate();
            }
        }
    };
    private final ControllerStub mController = new ControllerStub();
    private final SessionStub mSession = new SessionStub();
    private AudioManagerInternal mAudioManagerInternal = (AudioManagerInternal) LocalServices.getService(AudioManagerInternal.class);
    private AudioAttributes mAudioAttrs = new AudioAttributes.Builder().setUsage(1).build();

    public MediaSessionRecord(int ownerPid, int ownerUid, int userId, String ownerPackageName, ISessionCallback cb, String tag, MediaSessionService service, Looper handlerLooper) {
        this.mOwnerPid = ownerPid;
        this.mOwnerUid = ownerUid;
        this.mUserId = userId;
        this.mPackageName = ownerPackageName;
        this.mTag = tag;
        this.mSessionCb = new SessionCb(cb);
        this.mService = service;
        this.mContext = this.mService.getContext();
        this.mHandler = new MessageHandler(handlerLooper);
        this.mAudioManager = (AudioManager) this.mContext.getSystemService("audio");
    }

    public ISession getSessionBinder() {
        return this.mSession;
    }

    public ISessionController getControllerBinder() {
        return this.mController;
    }

    public String getPackageName() {
        return this.mPackageName;
    }

    public String getTag() {
        return this.mTag;
    }

    public PendingIntent getMediaButtonReceiver() {
        return this.mMediaButtonReceiver;
    }

    public long getFlags() {
        return this.mFlags;
    }

    public boolean hasFlag(int flag) {
        return (this.mFlags & ((long) flag)) != 0;
    }

    public int getUid() {
        return this.mOwnerUid;
    }

    public int getUserId() {
        return this.mUserId;
    }

    public boolean isSystemPriority() {
        return (this.mFlags & 65536) != 0;
    }

    public void adjustVolume(String packageName, int pid, int uid, ISessionControllerCallback caller, boolean asSystemService, int direction, int flags, boolean useSuggested) {
        int flags2;
        int previousFlagPlaySound = flags & 4;
        if (isPlaybackActive() || hasFlag(65536)) {
            flags2 = flags & (-5);
        } else {
            flags2 = flags;
        }
        if (this.mVolumeType == 1) {
            int stream = AudioAttributes.toLegacyStreamType(this.mAudioAttrs);
            postAdjustLocalVolume(stream, direction, flags2, packageName, uid, useSuggested, previousFlagPlaySound);
        } else if (this.mVolumeControlType == 0) {
        } else {
            if (direction == 101 || direction == -100 || direction == 100) {
                Log.w(TAG, "Muting remote playback is not supported");
                return;
            }
            this.mSessionCb.adjustVolume(packageName, pid, uid, caller, asSystemService, direction);
            int volumeBefore = this.mOptimisticVolume < 0 ? this.mCurrentVolume : this.mOptimisticVolume;
            this.mOptimisticVolume = volumeBefore + direction;
            this.mOptimisticVolume = Math.max(0, Math.min(this.mOptimisticVolume, this.mMaxVolume));
            this.mHandler.removeCallbacks(this.mClearOptimisticVolumeRunnable);
            this.mHandler.postDelayed(this.mClearOptimisticVolumeRunnable, 1000L);
            if (volumeBefore != this.mOptimisticVolume) {
                pushVolumeUpdate();
            }
            this.mService.notifyRemoteVolumeChanged(flags2, this);
            if (DEBUG) {
                Log.d(TAG, "Adjusted optimistic volume to " + this.mOptimisticVolume + " max is " + this.mMaxVolume);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setVolumeTo(String packageName, int pid, int uid, ISessionControllerCallback caller, int value, int flags) {
        if (this.mVolumeType == 1) {
            int stream = AudioAttributes.toLegacyStreamType(this.mAudioAttrs);
            this.mAudioManagerInternal.setStreamVolumeForUid(stream, value, flags, packageName, uid);
        } else if (this.mVolumeControlType != 2) {
        } else {
            int value2 = Math.max(0, Math.min(value, this.mMaxVolume));
            this.mSessionCb.setVolumeTo(packageName, pid, uid, caller, value2);
            int volumeBefore = this.mOptimisticVolume < 0 ? this.mCurrentVolume : this.mOptimisticVolume;
            this.mOptimisticVolume = Math.max(0, Math.min(value2, this.mMaxVolume));
            this.mHandler.removeCallbacks(this.mClearOptimisticVolumeRunnable);
            this.mHandler.postDelayed(this.mClearOptimisticVolumeRunnable, 1000L);
            if (volumeBefore != this.mOptimisticVolume) {
                pushVolumeUpdate();
            }
            this.mService.notifyRemoteVolumeChanged(flags, this);
            if (DEBUG) {
                Log.d(TAG, "Set optimistic volume to " + this.mOptimisticVolume + " max is " + this.mMaxVolume);
            }
        }
    }

    public boolean isActive() {
        return this.mIsActive && !this.mDestroyed;
    }

    public PlaybackState getPlaybackState() {
        return this.mPlaybackState;
    }

    public boolean isPlaybackActive() {
        int state = this.mPlaybackState == null ? 0 : this.mPlaybackState.getState();
        return MediaSession.isActiveState(state);
    }

    public int getPlaybackType() {
        return this.mVolumeType;
    }

    public AudioAttributes getAudioAttributes() {
        return this.mAudioAttrs;
    }

    public int getVolumeControl() {
        return this.mVolumeControlType;
    }

    public int getMaxVolume() {
        return this.mMaxVolume;
    }

    public int getCurrentVolume() {
        return this.mCurrentVolume;
    }

    public int getOptimisticVolume() {
        return this.mOptimisticVolume;
    }

    public boolean isTransportControlEnabled() {
        return hasFlag(2);
    }

    @Override // android.os.IBinder.DeathRecipient
    public void binderDied() {
        this.mService.sessionDied(this);
    }

    public void onDestroy() {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                return;
            }
            this.mDestroyed = true;
            this.mHandler.post(9);
        }
    }

    public ISessionCallback getCallback() {
        return this.mSessionCb.mCb;
    }

    public void sendMediaButton(String packageName, int pid, int uid, boolean asSystemService, KeyEvent ke, int sequenceId, ResultReceiver cb) {
        this.mSessionCb.sendMediaButton(packageName, pid, uid, asSystemService, ke, sequenceId, cb);
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + this.mTag + " " + this);
        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        sb.append("  ");
        String indent = sb.toString();
        pw.println(indent + "ownerPid=" + this.mOwnerPid + ", ownerUid=" + this.mOwnerUid + ", userId=" + this.mUserId);
        StringBuilder sb2 = new StringBuilder();
        sb2.append(indent);
        sb2.append("package=");
        sb2.append(this.mPackageName);
        pw.println(sb2.toString());
        pw.println(indent + "launchIntent=" + this.mLaunchIntent);
        pw.println(indent + "mediaButtonReceiver=" + this.mMediaButtonReceiver);
        pw.println(indent + "active=" + this.mIsActive);
        pw.println(indent + "flags=" + this.mFlags);
        pw.println(indent + "rating type=" + this.mRatingType);
        pw.println(indent + "controllers: " + this.mControllerCallbackHolders.size());
        StringBuilder sb3 = new StringBuilder();
        sb3.append(indent);
        sb3.append("state=");
        sb3.append(this.mPlaybackState == null ? null : this.mPlaybackState.toString());
        pw.println(sb3.toString());
        pw.println(indent + "audioAttrs=" + this.mAudioAttrs);
        pw.println(indent + "volumeType=" + this.mVolumeType + ", controlType=" + this.mVolumeControlType + ", max=" + this.mMaxVolume + ", current=" + this.mCurrentVolume);
        StringBuilder sb4 = new StringBuilder();
        sb4.append(indent);
        sb4.append("metadata:");
        sb4.append(getShortMetadataString());
        pw.println(sb4.toString());
        StringBuilder sb5 = new StringBuilder();
        sb5.append(indent);
        sb5.append("queueTitle=");
        sb5.append((Object) this.mQueueTitle);
        sb5.append(", size=");
        sb5.append(this.mQueue == null ? 0 : this.mQueue.getList().size());
        pw.println(sb5.toString());
    }

    public String toString() {
        return this.mPackageName + SliceClientPermissions.SliceAuthority.DELIMITER + this.mTag + " (userId=" + this.mUserId + ")";
    }

    private void postAdjustLocalVolume(final int stream, final int direction, final int flags, final String packageName, final int uid, final boolean useSuggested, final int previousFlagPlaySound) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.media.MediaSessionRecord.1
            @Override // java.lang.Runnable
            public void run() {
                try {
                    if (!useSuggested) {
                        MediaSessionRecord.this.mAudioManagerInternal.adjustStreamVolumeForUid(stream, direction, flags, packageName, uid);
                    } else if (AudioSystem.isStreamActive(stream, 0)) {
                        MediaSessionRecord.this.mAudioManagerInternal.adjustSuggestedStreamVolumeForUid(stream, direction, flags, packageName, uid);
                    } else {
                        MediaSessionRecord.this.mAudioManagerInternal.adjustSuggestedStreamVolumeForUid(Integer.MIN_VALUE, direction, previousFlagPlaySound | flags, packageName, uid);
                    }
                } catch (IllegalArgumentException e) {
                    Log.e(MediaSessionRecord.TAG, "Cannot adjust volume: direction=" + direction + ", stream=" + stream + ", flags=" + flags + ", packageName=" + packageName + ", uid=" + uid + ", useSuggested=" + useSuggested + ", previousFlagPlaySound=" + previousFlagPlaySound, e);
                }
            }
        });
    }

    private String getShortMetadataString() {
        int fields = this.mMetadata == null ? 0 : this.mMetadata.size();
        MediaDescription description = this.mMetadata == null ? null : this.mMetadata.getDescription();
        return "size=" + fields + ", description=" + description;
    }

    private void logCallbackException(String msg, ISessionControllerCallbackHolder holder, Exception e) {
        Log.v(TAG, msg + ", this=" + this + ", callback package=" + holder.mPackageName + ", exception=" + e);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pushPlaybackStateUpdate() {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                return;
            }
            for (int i = this.mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                ISessionControllerCallbackHolder holder = this.mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.onPlaybackStateChanged(this.mPlaybackState);
                } catch (DeadObjectException e) {
                    this.mControllerCallbackHolders.remove(i);
                    logCallbackException("Removed dead callback in pushPlaybackStateUpdate", holder, e);
                } catch (RemoteException e2) {
                    logCallbackException("unexpected exception in pushPlaybackStateUpdate", holder, e2);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pushMetadataUpdate() {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                return;
            }
            for (int i = this.mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                ISessionControllerCallbackHolder holder = this.mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.onMetadataChanged(this.mMetadata);
                } catch (DeadObjectException e) {
                    logCallbackException("Removing dead callback in pushMetadataUpdate", holder, e);
                    this.mControllerCallbackHolders.remove(i);
                } catch (RemoteException e2) {
                    logCallbackException("unexpected exception in pushMetadataUpdate", holder, e2);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pushQueueUpdate() {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                return;
            }
            for (int i = this.mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                ISessionControllerCallbackHolder holder = this.mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.onQueueChanged(this.mQueue);
                } catch (DeadObjectException e) {
                    this.mControllerCallbackHolders.remove(i);
                    logCallbackException("Removed dead callback in pushQueueUpdate", holder, e);
                } catch (RemoteException e2) {
                    logCallbackException("unexpected exception in pushQueueUpdate", holder, e2);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pushQueueTitleUpdate() {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                return;
            }
            for (int i = this.mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                ISessionControllerCallbackHolder holder = this.mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.onQueueTitleChanged(this.mQueueTitle);
                } catch (DeadObjectException e) {
                    this.mControllerCallbackHolders.remove(i);
                    logCallbackException("Removed dead callback in pushQueueTitleUpdate", holder, e);
                } catch (RemoteException e2) {
                    logCallbackException("unexpected exception in pushQueueTitleUpdate", holder, e2);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pushExtrasUpdate() {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                return;
            }
            for (int i = this.mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                ISessionControllerCallbackHolder holder = this.mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.onExtrasChanged(this.mExtras);
                } catch (DeadObjectException e) {
                    this.mControllerCallbackHolders.remove(i);
                    logCallbackException("Removed dead callback in pushExtrasUpdate", holder, e);
                } catch (RemoteException e2) {
                    logCallbackException("unexpected exception in pushExtrasUpdate", holder, e2);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pushVolumeUpdate() {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                return;
            }
            ParcelableVolumeInfo info = this.mController.getVolumeAttributes();
            for (int i = this.mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                ISessionControllerCallbackHolder holder = this.mControllerCallbackHolders.get(i);
                try {
                    try {
                        holder.mCallback.onVolumeInfoChanged(info);
                    } catch (RemoteException e) {
                        logCallbackException("Unexpected exception in pushVolumeUpdate", holder, e);
                    }
                } catch (DeadObjectException e2) {
                    this.mControllerCallbackHolders.remove(i);
                    logCallbackException("Removing dead callback in pushVolumeUpdate", holder, e2);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pushEvent(String event, Bundle data) {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                return;
            }
            for (int i = this.mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                ISessionControllerCallbackHolder holder = this.mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.onEvent(event, data);
                } catch (DeadObjectException e) {
                    this.mControllerCallbackHolders.remove(i);
                    logCallbackException("Removing dead callback in pushEvent", holder, e);
                } catch (RemoteException e2) {
                    logCallbackException("unexpected exception in pushEvent", holder, e2);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pushSessionDestroyed() {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                for (int i = this.mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                    ISessionControllerCallbackHolder holder = this.mControllerCallbackHolders.get(i);
                    try {
                        try {
                            holder.mCallback.onSessionDestroyed();
                        } catch (RemoteException e) {
                            logCallbackException("unexpected exception in pushEvent", holder, e);
                        }
                    } catch (DeadObjectException e2) {
                        logCallbackException("Removing dead callback in pushEvent", holder, e2);
                        this.mControllerCallbackHolders.remove(i);
                    }
                }
                this.mControllerCallbackHolders.clear();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:36:0x008f  */
    /* JADX WARN: Removed duplicated region for block: B:37:0x0092  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public android.media.session.PlaybackState getStateWithUpdatedPosition() {
        /*
            r19 = this;
            r1 = r19
            r2 = -1
            java.lang.Object r4 = r1.mLock
            monitor-enter(r4)
            android.media.session.PlaybackState r0 = r1.mPlaybackState     // Catch: java.lang.Throwable -> L94
            android.media.MediaMetadata r5 = r1.mMetadata     // Catch: java.lang.Throwable -> L94
            if (r5 == 0) goto L20
            android.media.MediaMetadata r5 = r1.mMetadata     // Catch: java.lang.Throwable -> L94
            java.lang.String r6 = "android.media.metadata.DURATION"
            boolean r5 = r5.containsKey(r6)     // Catch: java.lang.Throwable -> L94
            if (r5 == 0) goto L20
            android.media.MediaMetadata r5 = r1.mMetadata     // Catch: java.lang.Throwable -> L94
            java.lang.String r6 = "android.media.metadata.DURATION"
            long r5 = r5.getLong(r6)     // Catch: java.lang.Throwable -> L94
            r2 = r5
        L20:
            monitor-exit(r4)     // Catch: java.lang.Throwable -> L94
            r4 = 0
            if (r0 == 0) goto L8a
            int r5 = r0.getState()
            r6 = 3
            if (r5 == r6) goto L3d
            int r5 = r0.getState()
            r6 = 4
            if (r5 == r6) goto L3d
            int r5 = r0.getState()
            r6 = 5
            if (r5 != r6) goto L3a
            goto L3d
        L3a:
            r18 = r0
            goto L8c
        L3d:
            long r5 = r0.getLastPositionUpdateTime()
            long r14 = android.os.SystemClock.elapsedRealtime()
            r7 = 0
            int r9 = (r5 > r7 ? 1 : (r5 == r7 ? 0 : -1))
            if (r9 <= 0) goto L8a
            float r9 = r0.getPlaybackSpeed()
            long r10 = r14 - r5
            float r10 = (float) r10
            float r9 = r9 * r10
            long r9 = (long) r9
            long r11 = r0.getPosition()
            long r9 = r9 + r11
            int r11 = (r2 > r7 ? 1 : (r2 == r7 ? 0 : -1))
            if (r11 < 0) goto L65
            int r11 = (r9 > r2 ? 1 : (r9 == r2 ? 0 : -1))
            if (r11 <= 0) goto L65
            r7 = r2
        L62:
            r16 = r7
            goto L6e
        L65:
            int r7 = (r9 > r7 ? 1 : (r9 == r7 ? 0 : -1))
            if (r7 >= 0) goto L6c
            r7 = 0
            goto L62
        L6c:
            r16 = r9
        L6e:
            android.media.session.PlaybackState$Builder r7 = new android.media.session.PlaybackState$Builder
            r7.<init>(r0)
            r12 = r7
            int r8 = r0.getState()
            float r11 = r0.getPlaybackSpeed()
            r9 = r16
            r18 = r0
            r0 = r12
            r12 = r14
            r7.setState(r8, r9, r11, r12)
            android.media.session.PlaybackState r0 = r0.build()
            goto L8d
        L8a:
            r18 = r0
        L8c:
            r0 = r4
        L8d:
            if (r0 != 0) goto L92
            r4 = r18
            goto L93
        L92:
            r4 = r0
        L93:
            return r4
        L94:
            r0 = move-exception
            monitor-exit(r4)     // Catch: java.lang.Throwable -> L94
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.media.MediaSessionRecord.getStateWithUpdatedPosition():android.media.session.PlaybackState");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getControllerHolderIndexForCb(ISessionControllerCallback cb) {
        IBinder binder = cb.asBinder();
        for (int i = this.mControllerCallbackHolders.size() - 1; i >= 0; i--) {
            if (binder.equals(this.mControllerCallbackHolders.get(i).mCallback.asBinder())) {
                return i;
            }
        }
        return -1;
    }

    /* loaded from: classes.dex */
    private final class SessionStub extends ISession.Stub {
        private SessionStub() {
        }

        public void destroy() {
            long token = Binder.clearCallingIdentity();
            try {
                MediaSessionRecord.this.mService.destroySession(MediaSessionRecord.this);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void sendEvent(String event, Bundle data) {
            MediaSessionRecord.this.mHandler.post(6, event, data == null ? null : new Bundle(data));
        }

        public ISessionController getController() {
            return MediaSessionRecord.this.mController;
        }

        public void setActive(boolean active) {
            MediaSessionRecord.this.mIsActive = active;
            long token = Binder.clearCallingIdentity();
            try {
                MediaSessionRecord.this.mService.updateSession(MediaSessionRecord.this);
                Binder.restoreCallingIdentity(token);
                MediaSessionRecord.this.mHandler.post(7);
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(token);
                throw th;
            }
        }

        public void setFlags(int flags) {
            if ((flags & 65536) != 0) {
                int pid = getCallingPid();
                int uid = getCallingUid();
                MediaSessionRecord.this.mService.enforcePhoneStatePermission(pid, uid);
            }
            MediaSessionRecord.this.mFlags = flags;
            if ((65536 & flags) != 0) {
                long token = Binder.clearCallingIdentity();
                try {
                    MediaSessionRecord.this.mService.setGlobalPrioritySession(MediaSessionRecord.this);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
            MediaSessionRecord.this.mHandler.post(7);
        }

        public void setMediaButtonReceiver(PendingIntent pi) {
            MediaSessionRecord.this.mMediaButtonReceiver = pi;
            long token = Binder.clearCallingIdentity();
            try {
                MediaSessionRecord.this.mService.onMediaButtonReceiverChanged(MediaSessionRecord.this);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void setLaunchPendingIntent(PendingIntent pi) {
            MediaSessionRecord.this.mLaunchIntent = pi;
        }

        public void setMetadata(MediaMetadata metadata) {
            synchronized (MediaSessionRecord.this.mLock) {
                MediaMetadata temp = metadata == null ? null : new MediaMetadata.Builder(metadata).build();
                if (temp != null) {
                    temp.size();
                }
                MediaSessionRecord.this.mMetadata = temp;
            }
            MediaSessionRecord.this.mHandler.post(1);
        }

        public void setPlaybackState(PlaybackState state) {
            int oldState;
            if (MediaSessionRecord.this.mPlaybackState != null) {
                oldState = MediaSessionRecord.this.mPlaybackState.getState();
            } else {
                oldState = 0;
            }
            int newState = state != null ? state.getState() : 0;
            synchronized (MediaSessionRecord.this.mLock) {
                MediaSessionRecord.this.mPlaybackState = state;
            }
            long token = Binder.clearCallingIdentity();
            try {
                MediaSessionRecord.this.mService.onSessionPlaystateChanged(MediaSessionRecord.this, oldState, newState);
                Binder.restoreCallingIdentity(token);
                MediaSessionRecord.this.mHandler.post(2);
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(token);
                throw th;
            }
        }

        public void setQueue(ParceledListSlice queue) {
            synchronized (MediaSessionRecord.this.mLock) {
                MediaSessionRecord.this.mQueue = queue;
            }
            MediaSessionRecord.this.mHandler.post(3);
        }

        public void setQueueTitle(CharSequence title) {
            MediaSessionRecord.this.mQueueTitle = title;
            MediaSessionRecord.this.mHandler.post(4);
        }

        public void setExtras(Bundle extras) {
            synchronized (MediaSessionRecord.this.mLock) {
                MediaSessionRecord.this.mExtras = extras == null ? null : new Bundle(extras);
            }
            MediaSessionRecord.this.mHandler.post(5);
        }

        public void setRatingType(int type) {
            MediaSessionRecord.this.mRatingType = type;
        }

        public void setCurrentVolume(int volume) {
            MediaSessionRecord.this.mCurrentVolume = volume;
            MediaSessionRecord.this.mHandler.post(8);
        }

        public void setPlaybackToLocal(AudioAttributes attributes) {
            boolean typeChanged;
            synchronized (MediaSessionRecord.this.mLock) {
                typeChanged = MediaSessionRecord.this.mVolumeType == 2;
                MediaSessionRecord.this.mVolumeType = 1;
                if (attributes != null) {
                    MediaSessionRecord.this.mAudioAttrs = attributes;
                } else {
                    Log.e(MediaSessionRecord.TAG, "Received null audio attributes, using existing attributes");
                }
            }
            if (typeChanged) {
                long token = Binder.clearCallingIdentity();
                try {
                    MediaSessionRecord.this.mService.onSessionPlaybackTypeChanged(MediaSessionRecord.this);
                    Binder.restoreCallingIdentity(token);
                    MediaSessionRecord.this.mHandler.post(8);
                } catch (Throwable th) {
                    Binder.restoreCallingIdentity(token);
                    throw th;
                }
            }
        }

        public void setPlaybackToRemote(int control, int max) {
            boolean typeChanged;
            synchronized (MediaSessionRecord.this.mLock) {
                boolean z = true;
                if (MediaSessionRecord.this.mVolumeType != 1) {
                    z = false;
                }
                typeChanged = z;
                MediaSessionRecord.this.mVolumeType = 2;
                MediaSessionRecord.this.mVolumeControlType = control;
                MediaSessionRecord.this.mMaxVolume = max;
            }
            if (typeChanged) {
                long token = Binder.clearCallingIdentity();
                try {
                    MediaSessionRecord.this.mService.onSessionPlaybackTypeChanged(MediaSessionRecord.this);
                    Binder.restoreCallingIdentity(token);
                    MediaSessionRecord.this.mHandler.post(8);
                } catch (Throwable th) {
                    Binder.restoreCallingIdentity(token);
                    throw th;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class SessionCb {
        private final ISessionCallback mCb;

        public SessionCb(ISessionCallback cb) {
            this.mCb = cb;
        }

        public boolean sendMediaButton(String packageName, int pid, int uid, boolean asSystemService, KeyEvent keyEvent, int sequenceId, ResultReceiver cb) {
            try {
                if (asSystemService) {
                    this.mCb.onMediaButton(MediaSessionRecord.this.mContext.getPackageName(), Process.myPid(), 1000, createMediaButtonIntent(keyEvent), sequenceId, cb);
                    return true;
                }
                this.mCb.onMediaButton(packageName, pid, uid, createMediaButtonIntent(keyEvent), sequenceId, cb);
                return true;
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in sendMediaRequest.", e);
                return false;
            }
        }

        public boolean sendMediaButton(String packageName, int pid, int uid, ISessionControllerCallback caller, boolean asSystemService, KeyEvent keyEvent) {
            try {
                if (asSystemService) {
                    this.mCb.onMediaButton(MediaSessionRecord.this.mContext.getPackageName(), Process.myPid(), 1000, createMediaButtonIntent(keyEvent), 0, (ResultReceiver) null);
                    return true;
                }
                this.mCb.onMediaButtonFromController(packageName, pid, uid, caller, createMediaButtonIntent(keyEvent));
                return true;
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in sendMediaRequest.", e);
                return false;
            }
        }

        public void sendCommand(String packageName, int pid, int uid, ISessionControllerCallback caller, String command, Bundle args, ResultReceiver cb) {
            try {
                this.mCb.onCommand(packageName, pid, uid, caller, command, args, cb);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in sendCommand.", e);
            }
        }

        public void sendCustomAction(String packageName, int pid, int uid, ISessionControllerCallback caller, String action, Bundle args) {
            try {
                this.mCb.onCustomAction(packageName, pid, uid, caller, action, args);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in sendCustomAction.", e);
            }
        }

        public void prepare(String packageName, int pid, int uid, ISessionControllerCallback caller) {
            try {
                this.mCb.onPrepare(packageName, pid, uid, caller);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in prepare.", e);
            }
        }

        public void prepareFromMediaId(String packageName, int pid, int uid, ISessionControllerCallback caller, String mediaId, Bundle extras) {
            try {
                this.mCb.onPrepareFromMediaId(packageName, pid, uid, caller, mediaId, extras);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in prepareFromMediaId.", e);
            }
        }

        public void prepareFromSearch(String packageName, int pid, int uid, ISessionControllerCallback caller, String query, Bundle extras) {
            try {
                this.mCb.onPrepareFromSearch(packageName, pid, uid, caller, query, extras);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in prepareFromSearch.", e);
            }
        }

        public void prepareFromUri(String packageName, int pid, int uid, ISessionControllerCallback caller, Uri uri, Bundle extras) {
            try {
                this.mCb.onPrepareFromUri(packageName, pid, uid, caller, uri, extras);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in prepareFromUri.", e);
            }
        }

        public void play(String packageName, int pid, int uid, ISessionControllerCallback caller) {
            try {
                this.mCb.onPlay(packageName, pid, uid, caller);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in play.", e);
            }
        }

        public void playFromMediaId(String packageName, int pid, int uid, ISessionControllerCallback caller, String mediaId, Bundle extras) {
            try {
                this.mCb.onPlayFromMediaId(packageName, pid, uid, caller, mediaId, extras);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in playFromMediaId.", e);
            }
        }

        public void playFromSearch(String packageName, int pid, int uid, ISessionControllerCallback caller, String query, Bundle extras) {
            try {
                this.mCb.onPlayFromSearch(packageName, pid, uid, caller, query, extras);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in playFromSearch.", e);
            }
        }

        public void playFromUri(String packageName, int pid, int uid, ISessionControllerCallback caller, Uri uri, Bundle extras) {
            try {
                this.mCb.onPlayFromUri(packageName, pid, uid, caller, uri, extras);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in playFromUri.", e);
            }
        }

        public void skipToTrack(String packageName, int pid, int uid, ISessionControllerCallback caller, long id) {
            try {
                this.mCb.onSkipToTrack(packageName, pid, uid, caller, id);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in skipToTrack", e);
            }
        }

        public void pause(String packageName, int pid, int uid, ISessionControllerCallback caller) {
            try {
                this.mCb.onPause(packageName, pid, uid, caller);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in pause.", e);
            }
        }

        public void stop(String packageName, int pid, int uid, ISessionControllerCallback caller) {
            try {
                this.mCb.onStop(packageName, pid, uid, caller);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in stop.", e);
            }
        }

        public void next(String packageName, int pid, int uid, ISessionControllerCallback caller) {
            try {
                this.mCb.onNext(packageName, pid, uid, caller);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in next.", e);
            }
        }

        public void previous(String packageName, int pid, int uid, ISessionControllerCallback caller) {
            try {
                this.mCb.onPrevious(packageName, pid, uid, caller);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in previous.", e);
            }
        }

        public void fastForward(String packageName, int pid, int uid, ISessionControllerCallback caller) {
            try {
                this.mCb.onFastForward(packageName, pid, uid, caller);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in fastForward.", e);
            }
        }

        public void rewind(String packageName, int pid, int uid, ISessionControllerCallback caller) {
            try {
                this.mCb.onRewind(packageName, pid, uid, caller);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in rewind.", e);
            }
        }

        public void seekTo(String packageName, int pid, int uid, ISessionControllerCallback caller, long pos) {
            try {
                this.mCb.onSeekTo(packageName, pid, uid, caller, pos);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in seekTo.", e);
            }
        }

        public void rate(String packageName, int pid, int uid, ISessionControllerCallback caller, Rating rating) {
            try {
                this.mCb.onRate(packageName, pid, uid, caller, rating);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in rate.", e);
            }
        }

        public void adjustVolume(String packageName, int pid, int uid, ISessionControllerCallback caller, boolean asSystemService, int direction) {
            try {
                if (asSystemService) {
                    this.mCb.onAdjustVolume(MediaSessionRecord.this.mContext.getPackageName(), Process.myPid(), 1000, (ISessionControllerCallback) null, direction);
                } else {
                    this.mCb.onAdjustVolume(packageName, pid, uid, caller, direction);
                }
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in adjustVolume.", e);
            }
        }

        public void setVolumeTo(String packageName, int pid, int uid, ISessionControllerCallback caller, int value) {
            try {
                this.mCb.onSetVolumeTo(packageName, pid, uid, caller, value);
            } catch (RemoteException e) {
                Slog.e(MediaSessionRecord.TAG, "Remote failure in setVolumeTo.", e);
            }
        }

        private Intent createMediaButtonIntent(KeyEvent keyEvent) {
            Intent mediaButtonIntent = new Intent("android.intent.action.MEDIA_BUTTON");
            mediaButtonIntent.putExtra("android.intent.extra.KEY_EVENT", keyEvent);
            return mediaButtonIntent;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class ControllerStub extends ISessionController.Stub {
        ControllerStub() {
        }

        public void sendCommand(String packageName, ISessionControllerCallback caller, String command, Bundle args, ResultReceiver cb) {
            MediaSessionRecord.this.mSessionCb.sendCommand(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller, command, args, cb);
        }

        public boolean sendMediaButton(String packageName, ISessionControllerCallback cb, boolean asSystemService, KeyEvent keyEvent) {
            return MediaSessionRecord.this.mSessionCb.sendMediaButton(packageName, Binder.getCallingPid(), Binder.getCallingUid(), cb, asSystemService, keyEvent);
        }

        public void registerCallbackListener(String packageName, ISessionControllerCallback cb) {
            synchronized (MediaSessionRecord.this.mLock) {
                if (!MediaSessionRecord.this.mDestroyed) {
                    if (MediaSessionRecord.this.getControllerHolderIndexForCb(cb) < 0) {
                        MediaSessionRecord.this.mControllerCallbackHolders.add(new ISessionControllerCallbackHolder(cb, packageName, Binder.getCallingUid()));
                        if (MediaSessionRecord.DEBUG) {
                            Log.d(MediaSessionRecord.TAG, "registering controller callback " + cb + " from controller" + packageName);
                        }
                    }
                    return;
                }
                try {
                    cb.onSessionDestroyed();
                } catch (Exception e) {
                }
            }
        }

        public void unregisterCallbackListener(ISessionControllerCallback cb) {
            synchronized (MediaSessionRecord.this.mLock) {
                int index = MediaSessionRecord.this.getControllerHolderIndexForCb(cb);
                if (index != -1) {
                    MediaSessionRecord.this.mControllerCallbackHolders.remove(index);
                }
                if (MediaSessionRecord.DEBUG) {
                    Log.d(MediaSessionRecord.TAG, "unregistering callback " + cb.asBinder());
                }
            }
        }

        public String getPackageName() {
            return MediaSessionRecord.this.mPackageName;
        }

        public String getTag() {
            return MediaSessionRecord.this.mTag;
        }

        public PendingIntent getLaunchPendingIntent() {
            return MediaSessionRecord.this.mLaunchIntent;
        }

        public long getFlags() {
            return MediaSessionRecord.this.mFlags;
        }

        public ParcelableVolumeInfo getVolumeAttributes() {
            synchronized (MediaSessionRecord.this.mLock) {
                if (MediaSessionRecord.this.mVolumeType == 2) {
                    int current = MediaSessionRecord.this.mOptimisticVolume != -1 ? MediaSessionRecord.this.mOptimisticVolume : MediaSessionRecord.this.mCurrentVolume;
                    return new ParcelableVolumeInfo(MediaSessionRecord.this.mVolumeType, MediaSessionRecord.this.mAudioAttrs, MediaSessionRecord.this.mVolumeControlType, MediaSessionRecord.this.mMaxVolume, current);
                }
                int volumeType = MediaSessionRecord.this.mVolumeType;
                AudioAttributes attributes = MediaSessionRecord.this.mAudioAttrs;
                int stream = AudioAttributes.toLegacyStreamType(attributes);
                int max = MediaSessionRecord.this.mAudioManager.getStreamMaxVolume(stream);
                int current2 = MediaSessionRecord.this.mAudioManager.getStreamVolume(stream);
                return new ParcelableVolumeInfo(volumeType, attributes, 2, max, current2);
            }
        }

        public void adjustVolume(String packageName, ISessionControllerCallback caller, boolean asSystemService, int direction, int flags) {
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            long token = Binder.clearCallingIdentity();
            try {
                MediaSessionRecord.this.adjustVolume(packageName, pid, uid, caller, asSystemService, direction, flags, false);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void setVolumeTo(String packageName, ISessionControllerCallback caller, int value, int flags) {
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            long token = Binder.clearCallingIdentity();
            try {
                MediaSessionRecord.this.setVolumeTo(packageName, pid, uid, caller, value, flags);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void prepare(String packageName, ISessionControllerCallback caller) {
            MediaSessionRecord.this.mSessionCb.prepare(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller);
        }

        public void prepareFromMediaId(String packageName, ISessionControllerCallback caller, String mediaId, Bundle extras) {
            MediaSessionRecord.this.mSessionCb.prepareFromMediaId(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller, mediaId, extras);
        }

        public void prepareFromSearch(String packageName, ISessionControllerCallback caller, String query, Bundle extras) {
            MediaSessionRecord.this.mSessionCb.prepareFromSearch(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller, query, extras);
        }

        public void prepareFromUri(String packageName, ISessionControllerCallback caller, Uri uri, Bundle extras) {
            MediaSessionRecord.this.mSessionCb.prepareFromUri(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller, uri, extras);
        }

        public void play(String packageName, ISessionControllerCallback caller) {
            MediaSessionRecord.this.mSessionCb.play(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller);
        }

        public void playFromMediaId(String packageName, ISessionControllerCallback caller, String mediaId, Bundle extras) {
            MediaSessionRecord.this.mSessionCb.playFromMediaId(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller, mediaId, extras);
        }

        public void playFromSearch(String packageName, ISessionControllerCallback caller, String query, Bundle extras) {
            MediaSessionRecord.this.mSessionCb.playFromSearch(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller, query, extras);
        }

        public void playFromUri(String packageName, ISessionControllerCallback caller, Uri uri, Bundle extras) {
            MediaSessionRecord.this.mSessionCb.playFromUri(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller, uri, extras);
        }

        public void skipToQueueItem(String packageName, ISessionControllerCallback caller, long id) {
            MediaSessionRecord.this.mSessionCb.skipToTrack(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller, id);
        }

        public void pause(String packageName, ISessionControllerCallback caller) {
            MediaSessionRecord.this.mSessionCb.pause(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller);
        }

        public void stop(String packageName, ISessionControllerCallback caller) {
            MediaSessionRecord.this.mSessionCb.stop(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller);
        }

        public void next(String packageName, ISessionControllerCallback caller) {
            MediaSessionRecord.this.mSessionCb.next(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller);
        }

        public void previous(String packageName, ISessionControllerCallback caller) {
            MediaSessionRecord.this.mSessionCb.previous(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller);
        }

        public void fastForward(String packageName, ISessionControllerCallback caller) {
            MediaSessionRecord.this.mSessionCb.fastForward(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller);
        }

        public void rewind(String packageName, ISessionControllerCallback caller) {
            MediaSessionRecord.this.mSessionCb.rewind(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller);
        }

        public void seekTo(String packageName, ISessionControllerCallback caller, long pos) {
            MediaSessionRecord.this.mSessionCb.seekTo(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller, pos);
        }

        public void rate(String packageName, ISessionControllerCallback caller, Rating rating) {
            MediaSessionRecord.this.mSessionCb.rate(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller, rating);
        }

        public void sendCustomAction(String packageName, ISessionControllerCallback caller, String action, Bundle args) {
            MediaSessionRecord.this.mSessionCb.sendCustomAction(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller, action, args);
        }

        public MediaMetadata getMetadata() {
            MediaMetadata mediaMetadata;
            synchronized (MediaSessionRecord.this.mLock) {
                mediaMetadata = MediaSessionRecord.this.mMetadata;
            }
            return mediaMetadata;
        }

        public PlaybackState getPlaybackState() {
            return MediaSessionRecord.this.getStateWithUpdatedPosition();
        }

        public ParceledListSlice getQueue() {
            ParceledListSlice parceledListSlice;
            synchronized (MediaSessionRecord.this.mLock) {
                parceledListSlice = MediaSessionRecord.this.mQueue;
            }
            return parceledListSlice;
        }

        public CharSequence getQueueTitle() {
            return MediaSessionRecord.this.mQueueTitle;
        }

        public Bundle getExtras() {
            Bundle bundle;
            synchronized (MediaSessionRecord.this.mLock) {
                bundle = MediaSessionRecord.this.mExtras;
            }
            return bundle;
        }

        public int getRatingType() {
            return MediaSessionRecord.this.mRatingType;
        }

        public boolean isTransportControlEnabled() {
            return MediaSessionRecord.this.isTransportControlEnabled();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class ISessionControllerCallbackHolder {
        private final ISessionControllerCallback mCallback;
        private final String mPackageName;
        private final int mUid;

        ISessionControllerCallbackHolder(ISessionControllerCallback callback, String packageName, int uid) {
            this.mCallback = callback;
            this.mPackageName = packageName;
            this.mUid = uid;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class MessageHandler extends Handler {
        private static final int MSG_DESTROYED = 9;
        private static final int MSG_SEND_EVENT = 6;
        private static final int MSG_UPDATE_EXTRAS = 5;
        private static final int MSG_UPDATE_METADATA = 1;
        private static final int MSG_UPDATE_PLAYBACK_STATE = 2;
        private static final int MSG_UPDATE_QUEUE = 3;
        private static final int MSG_UPDATE_QUEUE_TITLE = 4;
        private static final int MSG_UPDATE_SESSION_STATE = 7;
        private static final int MSG_UPDATE_VOLUME = 8;

        public MessageHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    MediaSessionRecord.this.pushMetadataUpdate();
                    return;
                case 2:
                    MediaSessionRecord.this.pushPlaybackStateUpdate();
                    return;
                case 3:
                    MediaSessionRecord.this.pushQueueUpdate();
                    return;
                case 4:
                    MediaSessionRecord.this.pushQueueTitleUpdate();
                    return;
                case 5:
                    MediaSessionRecord.this.pushExtrasUpdate();
                    return;
                case 6:
                    MediaSessionRecord.this.pushEvent((String) msg.obj, msg.getData());
                    return;
                case 7:
                default:
                    return;
                case 8:
                    MediaSessionRecord.this.pushVolumeUpdate();
                    return;
                case 9:
                    MediaSessionRecord.this.pushSessionDestroyed();
                    return;
            }
        }

        public void post(int what) {
            post(what, null);
        }

        public void post(int what, Object obj) {
            obtainMessage(what, obj).sendToTarget();
        }

        public void post(int what, Object obj, Bundle data) {
            Message msg = obtainMessage(what, obj);
            msg.setData(data);
            msg.sendToTarget();
        }
    }
}
