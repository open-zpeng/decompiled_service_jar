package com.android.server.media;

import android.content.Context;
import android.media.AudioPlaybackConfiguration;
import android.media.IAudioService;
import android.media.IPlaybackConfigDispatcher;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Log;
import com.android.internal.annotations.GuardedBy;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class AudioPlayerStateMonitor extends IPlaybackConfigDispatcher.Stub {
    private static boolean DEBUG = MediaSessionService.DEBUG;
    private static String TAG = "AudioPlayerStateMonitor";
    private static AudioPlayerStateMonitor sInstance = new AudioPlayerStateMonitor();
    @GuardedBy("mLock")
    private boolean mRegisteredToAudioService;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<OnAudioPlayerActiveStateChangedListener, MessageHandler> mListenerMap = new ArrayMap();
    @GuardedBy("mLock")
    private final Set<Integer> mActiveAudioUids = new ArraySet();
    @GuardedBy("mLock")
    private ArrayMap<Integer, AudioPlaybackConfiguration> mPrevActiveAudioPlaybackConfigs = new ArrayMap<>();
    @GuardedBy("mLock")
    private final IntArray mSortedAudioPlaybackClientUids = new IntArray();

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface OnAudioPlayerActiveStateChangedListener {
        void onAudioPlayerActiveStateChanged(AudioPlaybackConfiguration audioPlaybackConfiguration, boolean z);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class MessageHandler extends Handler {
        private static final int MSG_AUDIO_PLAYER_ACTIVE_STATE_CHANGED = 1;
        private final OnAudioPlayerActiveStateChangedListener mListener;

        MessageHandler(Looper looper, OnAudioPlayerActiveStateChangedListener listener) {
            super(looper);
            this.mListener = listener;
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
                this.mListener.onAudioPlayerActiveStateChanged((AudioPlaybackConfiguration) msg.obj, msg.arg1 != 0);
            }
        }

        void sendAudioPlayerActiveStateChangedMessage(AudioPlaybackConfiguration config, boolean isRemoved) {
            obtainMessage(1, isRemoved ? 1 : 0, 0, config).sendToTarget();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static AudioPlayerStateMonitor getInstance() {
        return sInstance;
    }

    private AudioPlayerStateMonitor() {
    }

    public void dispatchPlaybackConfigChange(List<AudioPlaybackConfiguration> configs, boolean flush) {
        if (flush) {
            Binder.flushPendingCommands();
        }
        long token = Binder.clearCallingIdentity();
        try {
            synchronized (this.mLock) {
                this.mActiveAudioUids.clear();
                ArrayMap<Integer, AudioPlaybackConfiguration> activeAudioPlaybackConfigs = new ArrayMap<>();
                for (AudioPlaybackConfiguration config : configs) {
                    if (config.isActive()) {
                        this.mActiveAudioUids.add(Integer.valueOf(config.getClientUid()));
                        activeAudioPlaybackConfigs.put(Integer.valueOf(config.getPlayerInterfaceId()), config);
                    }
                }
                for (int i = 0; i < activeAudioPlaybackConfigs.size(); i++) {
                    AudioPlaybackConfiguration config2 = activeAudioPlaybackConfigs.valueAt(i);
                    int uid = config2.getClientUid();
                    if (!this.mPrevActiveAudioPlaybackConfigs.containsKey(Integer.valueOf(config2.getPlayerInterfaceId()))) {
                        if (DEBUG) {
                            Log.d(TAG, "Found a new active media playback. " + AudioPlaybackConfiguration.toLogFriendlyString(config2));
                        }
                        int index = this.mSortedAudioPlaybackClientUids.indexOf(uid);
                        if (index != 0) {
                            if (index > 0) {
                                this.mSortedAudioPlaybackClientUids.remove(index);
                            }
                            this.mSortedAudioPlaybackClientUids.add(0, uid);
                        }
                    }
                }
                Iterator<AudioPlaybackConfiguration> it = configs.iterator();
                while (true) {
                    boolean wasActive = true;
                    if (!it.hasNext()) {
                        break;
                    }
                    AudioPlaybackConfiguration config3 = it.next();
                    int pii = config3.getPlayerInterfaceId();
                    if (this.mPrevActiveAudioPlaybackConfigs.remove(Integer.valueOf(pii)) == null) {
                        wasActive = false;
                    }
                    if (wasActive != config3.isActive()) {
                        sendAudioPlayerActiveStateChangedMessageLocked(config3, false);
                    }
                }
                for (AudioPlaybackConfiguration config4 : this.mPrevActiveAudioPlaybackConfigs.values()) {
                    sendAudioPlayerActiveStateChangedMessageLocked(config4, true);
                }
                this.mPrevActiveAudioPlaybackConfigs = activeAudioPlaybackConfigs;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void registerListener(OnAudioPlayerActiveStateChangedListener listener, Handler handler) {
        synchronized (this.mLock) {
            this.mListenerMap.put(listener, new MessageHandler(handler == null ? Looper.myLooper() : handler.getLooper(), listener));
        }
    }

    public void unregisterListener(OnAudioPlayerActiveStateChangedListener listener) {
        synchronized (this.mLock) {
            this.mListenerMap.remove(listener);
        }
    }

    public IntArray getSortedAudioPlaybackClientUids() {
        IntArray sortedAudioPlaybackClientUids = new IntArray();
        synchronized (this.mLock) {
            sortedAudioPlaybackClientUids.addAll(this.mSortedAudioPlaybackClientUids);
        }
        return sortedAudioPlaybackClientUids;
    }

    public boolean isPlaybackActive(int uid) {
        boolean contains;
        synchronized (this.mLock) {
            contains = this.mActiveAudioUids.contains(Integer.valueOf(uid));
        }
        return contains;
    }

    public void cleanUpAudioPlaybackUids(int mediaButtonSessionUid) {
        synchronized (this.mLock) {
            int userId = UserHandle.getUserId(mediaButtonSessionUid);
            for (int i = this.mSortedAudioPlaybackClientUids.size() - 1; i >= 0 && this.mSortedAudioPlaybackClientUids.get(i) != mediaButtonSessionUid; i--) {
                int uid = this.mSortedAudioPlaybackClientUids.get(i);
                if (userId == UserHandle.getUserId(uid) && !isPlaybackActive(uid)) {
                    this.mSortedAudioPlaybackClientUids.remove(i);
                }
            }
        }
    }

    public void dump(Context context, PrintWriter pw, String prefix) {
        synchronized (this.mLock) {
            pw.println(prefix + "Audio playback (lastly played comes first)");
            String indent = prefix + "  ";
            for (int i = 0; i < this.mSortedAudioPlaybackClientUids.size(); i++) {
                int uid = this.mSortedAudioPlaybackClientUids.get(i);
                pw.print(indent + "uid=" + uid + " packages=");
                String[] packages = context.getPackageManager().getPackagesForUid(uid);
                if (packages != null && packages.length > 0) {
                    for (int j = 0; j < packages.length; j++) {
                        pw.print(packages[j] + " ");
                    }
                }
                pw.println();
            }
        }
    }

    public void registerSelfIntoAudioServiceIfNeeded(IAudioService audioService) {
        synchronized (this.mLock) {
            try {
                if (!this.mRegisteredToAudioService) {
                    audioService.registerPlaybackCallback(this);
                    this.mRegisteredToAudioService = true;
                }
            } catch (RemoteException e) {
                Log.wtf(TAG, "Failed to register playback callback", e);
                this.mRegisteredToAudioService = false;
            }
        }
    }

    @GuardedBy("mLock")
    private void sendAudioPlayerActiveStateChangedMessageLocked(AudioPlaybackConfiguration config, boolean isRemoved) {
        for (MessageHandler messageHandler : this.mListenerMap.values()) {
            messageHandler.sendAudioPlayerActiveStateChangedMessage(config, isRemoved);
        }
    }
}
