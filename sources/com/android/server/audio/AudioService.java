package com.android.server.audio;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IUidObserver;
import android.app.NotificationManager;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.bluetooth.BluetoothDevice;
import android.car.hardware.XpVehicle.IXpVehicle;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.ContentObserver;
import android.hardware.hdmi.HdmiAudioSystemClient;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiPlaybackClient;
import android.hardware.hdmi.HdmiTvClient;
import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;
import android.media.AudioRoutesInfo;
import android.media.AudioSystem;
import android.media.IAudioEventListener;
import android.media.IAudioFocusDispatcher;
import android.media.IAudioRoutesObserver;
import android.media.IAudioServerStateDispatcher;
import android.media.IAudioService;
import android.media.IPlaybackConfigDispatcher;
import android.media.IRecordingConfigDispatcher;
import android.media.IRingtonePlayer;
import android.media.IVolumeController;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.media.PlayerBase;
import android.media.SoundEffectParms;
import android.media.SoundField;
import android.media.SoundPool;
import android.media.VolumePolicy;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioPolicyConfig;
import android.media.audiopolicy.AudioProductStrategy;
import android.media.audiopolicy.AudioVolumeGroup;
import android.media.audiopolicy.IAudioPolicyCallback;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionCallback;
import android.media.projection.IMediaProjectionManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.notification.ZenModeConfig;
import android.text.TextUtils;
import android.util.AndroidRuntimeException;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseIntArray;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.audio.AudioEventLogger;
import com.android.server.audio.AudioServiceEvents;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.UserManagerService;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.utils.PriorityDump;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.xpeng.audio.xpAudio;
import com.xiaopeng.app.ActivityInfoManager;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.audio.xpAudioSessionInfo;
import com.xiaopeng.audio.xuiKaraoke.IKaraoke;
import com.xiaopeng.server.app.xpPackageManagerService;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.xpaudiopolicy.XpAudioPolicy;
import com.xiaopeng.xui.xuiaudio.utils.remoteConnect;
import com.xiaopeng.xui.xuiaudio.xuiAudioPolicyTrigger;
import com.xiaopeng.xuimanager.IXUIService;
import com.xiaopeng.xuimanager.soundresource.ISoundResource;
import com.xiaopeng.xuimanager.soundresource.ISoundResourceListener;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.xmlpull.v1.XmlPullParserException;

/* loaded from: classes.dex */
public class AudioService extends IAudioService.Stub implements AccessibilityManager.TouchExplorationStateChangeListener, AccessibilityManager.AccessibilityServicesStateChangeListener {
    private static final String ASSET_FILE_VERSION = "1.0";
    private static final String ATTR_ASSET_FILE = "file";
    private static final String ATTR_ASSET_ID = "id";
    private static final String ATTR_GROUP_NAME = "name";
    private static final String ATTR_VERSION = "version";
    private static final String AVAS_STREAM_ENABLE = "avas_speaker";
    @VisibleForTesting
    public static final int BECOMING_NOISY_DELAY_MS = 1000;
    static final int CONNECTION_STATE_CONNECTED = 1;
    static final int CONNECTION_STATE_DISCONNECTED = 0;
    protected static final boolean DEBUG_AP = false;
    protected static final boolean DEBUG_DEVICES = false;
    protected static final boolean DEBUG_MODE = false;
    protected static final boolean DEBUG_VOL = true;
    private static final String DEFAULT_SOUND_THEME_PATH = "/system/media/audio/sndresource/theme/default";
    private static final int DEFAULT_STREAM_TYPE_OVERRIDE_DELAY_MS = 0;
    protected static final int DEFAULT_VOL_STREAM_NO_PLAYBACK = 3;
    private static final int DEVICE_MEDIA_UNMUTED_ON_PLUG = 67266444;
    private static final int FLAG_ADJUST_VOLUME = 1;
    private static final String GROUP_TOUCH_SOUNDS = "touch_sounds";
    private static final int INDICATE_SYSTEM_READY_RETRY_DELAY_MS = 1000;
    private static final String KARAOKE_SERVICE = "karaoke";
    static final int LOG_NB_EVENTS_DEVICE_CONNECTION = 30;
    static final int LOG_NB_EVENTS_DYN_POLICY = 10;
    static final int LOG_NB_EVENTS_FORCE_USE = 20;
    static final int LOG_NB_EVENTS_PHONE_STATE = 20;
    static final int LOG_NB_EVENTS_VOLUME = 40;
    private static final int MSG_ACCESSORY_PLUG_MEDIA_UNMUTE = 21;
    private static final int MSG_AUDIO_SERVER_DIED = 4;
    private static final int MSG_BT_HEADSET_CNCT_FAILED = 9;
    private static final int MSG_CHECK_MUSIC_ACTIVE = 11;
    private static final int MSG_CONFIGURE_SAFE_MEDIA_VOLUME = 12;
    private static final int MSG_CONFIGURE_SAFE_MEDIA_VOLUME_FORCED = 13;
    private static final int MSG_DISABLE_AUDIO_FOR_UID = 100;
    private static final int MSG_DISPATCH_AUDIO_SERVER_STATE = 23;
    private static final int MSG_DYN_POLICY_MIX_STATE_UPDATE = 19;
    private static final int MSG_ENABLE_SURROUND_FORMATS = 24;
    private static final int MSG_HDMI_VOLUME_CHECK = 28;
    private static final int MSG_INDICATE_SYSTEM_READY = 20;
    private static final int MSG_LOAD_SOUND_EFFECTS = 7;
    private static final int MSG_NOTIFY_VOL_EVENT = 22;
    private static final int MSG_OBSERVE_DEVICES_FOR_ALL_STREAMS = 27;
    private static final int MSG_PERSIST_MUSIC_ACTIVE_MS = 17;
    private static final int MSG_PERSIST_RINGER_MODE = 3;
    private static final int MSG_PERSIST_SAFE_VOLUME_STATE = 14;
    private static final int MSG_PERSIST_VOLUME = 1;
    private static final int MSG_PLAYBACK_CONFIG_CHANGE = 29;
    private static final int MSG_PLAY_SOUND_EFFECT = 5;
    private static final int MSG_SET_ALL_VOLUMES = 10;
    private static final int MSG_SET_DEVICE_STREAM_VOLUME = 26;
    private static final int MSG_SET_DEVICE_VOLUME = 0;
    private static final int MSG_SET_FORCE_USE = 8;
    private static final int MSG_SYSTEM_READY = 16;
    private static final int MSG_UNLOAD_SOUND_EFFECTS = 15;
    private static final int MSG_UNMUTE_STREAM = 18;
    private static final int MSG_UPDATE_RINGER_MODE = 25;
    private static final int MUSIC_ACTIVE_POLL_PERIOD_MS = 60000;
    private static final int NUM_SOUNDPOOL_CHANNELS = 4;
    private static final int PERSIST_DELAY = 500;
    private static final String PROPERTY_SOUND_THEME_PATH = "persist.xiaopeng.soundtheme.path";
    private static final int SAFE_MEDIA_VOLUME_ACTIVE = 3;
    private static final int SAFE_MEDIA_VOLUME_DISABLED = 1;
    private static final int SAFE_MEDIA_VOLUME_INACTIVE = 2;
    private static final int SAFE_MEDIA_VOLUME_NOT_CONFIGURED = 0;
    private static final int SAFE_VOLUME_CONFIGURE_TIMEOUT_MS = 30000;
    private static final int SENDMSG_NOOP = 1;
    private static final int SENDMSG_QUEUE = 2;
    private static final int SENDMSG_REPLACE = 0;
    private static final int SOUND_EFFECTS_LOAD_TIMEOUT_MS = 5000;
    private static final String SOUND_EFFECTS_PATH = "/media/audio/xiaopeng/cdu/wav/";
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_DISCONNECTED = 0;
    private static final String SYSTEMUI_PACKAGE_NAME = "com.android.systemui";
    private static final String SYSTEM_MIC_MUTE = "persist.sys.mic.mute";
    private static final String SYSTEM_SOUND_CONFIG = "persist.audio.system_sound";
    private static final String SystemSoundPath = "/play/system/";
    private static final String TAG = "AS.AudioService";
    private static final String TAG_ASSET = "asset";
    private static final String TAG_AUDIO_ASSETS = "audio_assets";
    private static final String TAG_GROUP = "group";
    private static final int TOUCH_EXPLORE_STREAM_TYPE_OVERRIDE_DELAY_MS = 1000;
    private static final int UNMUTE_STREAM_DELAY = 350;
    private static final int UNSAFE_VOLUME_MUSIC_ACTIVE_MS_MAX = 72000000;
    private static final String XUI_SERVICE_CLASS = "com.xiaopeng.xuiservice.XUIService";
    private static final String XUI_SERVICE_INTERFACE_NAME = "com.xiaopeng.xuimanager.IXUIService";
    private static final String XUI_SERVICE_PACKAGE = "com.xiaopeng.xuiservice";
    private static final int ZEN_VOLUME = 8;
    protected static int[] mStreamVolumeAlias;
    private static XpAudioEventListener mXpAudioEventListener;
    private static AudioEventHandler mkHandler;
    private static int sSoundEffectVolumeDb;
    private static int sStreamOverrideDelayMs;
    private int[] mAccessibilityServiceUids;
    private final AppOpsManager mAppOps;
    @GuardedBy({"mSettingsLock"})
    private int mAssistantUid;
    private PowerManager.WakeLock mAudioEventWakeLock;
    private AudioHandler mAudioHandler;
    private AudioSystemThread mAudioSystemThread;
    @GuardedBy({"mSettingsLock"})
    private boolean mCameraSoundForced;
    private int mConnectionState;
    private final ContentResolver mContentResolver;
    private final Context mContext;
    private final AudioDeviceBroker mDeviceBroker;
    private String mEnabledSurroundFormats;
    private int mEncodedSurroundMode;
    private IAudioPolicyCallback mExtVolumeController;
    private final boolean mHasVibrator;
    @GuardedBy({"mHdmiClientLock"})
    private HdmiAudioSystemClient mHdmiAudioSystemClient;
    private boolean mHdmiCecSink;
    @GuardedBy({"mHdmiClientLock"})
    private HdmiControlManager mHdmiManager;
    @GuardedBy({"mHdmiClientLock"})
    private HdmiPlaybackClient mHdmiPlaybackClient;
    @GuardedBy({"mHdmiClientLock"})
    private HdmiTvClient mHdmiTvClient;
    private final boolean mIsSingleVolume;
    private long mLoweredFromNormalToVibrateTime;
    private final MediaFocusControl mMediaFocusControl;
    private final boolean mMonitorRotation;
    private int mMusicActiveMs;
    private int mMuteAffectedStreams;
    private NotificationManager mNm;
    private StreamVolumeCommand mPendingVolumeCommand;
    private final int mPlatformType;
    private final PlaybackActivityMonitor mPlaybackMonitor;
    private IMediaProjectionManager mProjectionService;
    private final RecordingActivityMonitor mRecordMonitor;
    remoteConnect mRemoteConnect;
    private int mRingerAndZenModeMutedStreams;
    @GuardedBy({"mSettingsLock"})
    private int mRingerMode;
    private AudioManagerInternal.RingerModeDelegate mRingerModeDelegate;
    private volatile IRingtonePlayer mRingtonePlayer;
    RoleObserver mRoleObserver;
    private int mSafeMediaVolumeIndex;
    private int mSafeMediaVolumeState;
    private float mSafeUsbMediaVolumeDbfs;
    private int mSafeUsbMediaVolumeIndex;
    private SettingsObserver mSettingsObserver;
    private SoundPool mSoundPool;
    private SoundPoolCallback mSoundPoolCallBack;
    private SoundPoolListenerThread mSoundPoolListenerThread;
    ISoundResource mSoundResource;
    private VolumeStreamState[] mStreamStates;
    private boolean mSurroundModeChanged;
    private boolean mSystemReady;
    private boolean mSystemSoundOn;
    private final boolean mUseFixedVolume;
    private boolean mUserSwitchedReceived;
    private int mVibrateSetting;
    private Vibrator mVibrator;
    private IKaraoke mXMic;
    private IXUIService mXUIService;
    private xpAudio mXpAudio;
    private XpAudioPolicy mXpAudioPloicy;
    private xuiAudioPolicyTrigger mXuiAudioPolicyTrigger;
    private static final int mXuiVolPolicy = SystemProperties.getInt("persist.adjustvolume.policy", 2);
    private static int mCurActiveStream = -1;
    private static boolean mActiveStreamStatus = false;
    private static ExecutorService mFixedThreadExecutor = null;
    private static final boolean newPolicyOpen = AudioManager.newPolicyOpen;
    private static boolean avasStreamEnable = false;
    private static final List<String> SOUND_EFFECT_FILES = new ArrayList();
    protected static int[] MAX_STREAM_VOLUME = {5, 7, 7, 15, 7, 7, 15, 7, 15, 15, 15, 15, 15, 15};
    protected static int[] MIN_STREAM_VOLUME = {1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0};
    private static final int[] STREAM_VOLUME_OPS = {34, 36, 35, 36, 37, 38, 39, 36, 36, 36, 64, 36, 36, 36};
    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder().setContentType(4).setUsage(13).build();
    private static boolean sIndependentA11yVolume = false;
    static final AudioEventLogger sDeviceLogger = new AudioEventLogger(30, "wired/A2DP/hearing aid device connection");
    static final AudioEventLogger sForceUseLogger = new AudioEventLogger(20, "force use (logged before setForceUse() is executed)");
    static final AudioEventLogger sVolumeLogger = new AudioEventLogger(40, "volume changes (logged when command received by AudioService)");
    private static final String[] RINGER_MODE_NAMES = {"SILENT", "VIBRATE", PriorityDump.PRIORITY_ARG_NORMAL};
    private int savedCurVolume = -1;
    private boolean isZenVolChanged = false;
    private final boolean SYSTEM_SOUND_DEFAULT = true;
    private final Object mSystemSoundLock = new Object();
    private final VolumeController mVolumeController = new VolumeController();
    private int mMode = 0;
    private final Object mSettingsLock = new Object();
    private final Object mSoundEffectsLock = new Object();
    private final int[][] SOUND_EFFECT_FILES_MAP = (int[][]) Array.newInstance(int.class, 24, 2);
    private final int[] STREAM_VOLUME_ALIAS_VOICE = {0, 2, 2, 3, 4, 2, 6, 2, 2, 3, 3, 3, 3, 3};
    private final int[] STREAM_VOLUME_ALIAS_TELEVISION = {3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3};
    private final int[] STREAM_VOLUME_ALIAS_DEFAULT = {0, 2, 2, 3, 4, 2, 6, 2, 2, 3, 3, 3, 3, 3};
    private final AudioSystem.ErrorCallback mAudioSystemCallback = new AudioSystem.ErrorCallback() { // from class: com.android.server.audio.AudioService.1
        public void onError(int error) {
            if (error == 100 && AudioService.this.mRecordMonitor != null && AudioService.this.mAudioHandler != null) {
                AudioService.this.mRecordMonitor.onAudioServerDied();
                AudioService.sendMsg(AudioService.this.mAudioHandler, 4, 1, 0, 0, null, 0);
                AudioService.sendMsg(AudioService.this.mAudioHandler, 23, 2, 0, 0, null, 0);
            }
        }
    };
    @GuardedBy({"mSettingsLock"})
    private int mRingerModeExternal = -1;
    private int mRingerModeAffectedStreams = 0;
    private int mZenModeAffectedStreams = 0;
    private final BroadcastReceiver mReceiver = new AudioServiceBroadcastReceiver();
    private final UserManagerInternal.UserRestrictionsListener mUserRestrictionsListener = new AudioServiceUserRestrictionsListener();
    @GuardedBy({"mDeviceBroker.mSetModeLock"})
    final ArrayList<SetModeDeathHandler> mSetModeDeathHandlers = new ArrayList<>();
    private Looper mSoundPoolLooper = null;
    private int mPrevVolDirection = 0;
    private int mVolumeControlStream = -1;
    private boolean mUserSelectedVolumeControlStream = false;
    private final Object mForceControlStreamLock = new Object();
    private ForceControlStreamClient mForceControlStreamClient = null;
    int mFixedVolumeDevices = 2890752;
    int mFullVolumeDevices = 0;
    int mAbsVolumeMultiModeCaseDevices = 134217728;
    private boolean mDockAudioMediaEnabled = true;
    private int mDockState = 0;
    private float[] mPrescaleAbsoluteVolume = {0.5f, 0.7f, 0.85f};
    private VolumePolicy mVolumePolicy = VolumePolicy.DEFAULT;
    private final Object mAccessibilityServiceUidsLock = new Object();
    private final ServiceConnection mXMicConnectionListener = new ServiceConnection() { // from class: com.android.server.audio.AudioService.2
        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            AudioService.this.mXUIService = IXUIService.Stub.asInterface(service);
            AudioService.this.mConnectionState = 2;
            IBinder binder = null;
            try {
                binder = AudioService.this.mXUIService.getXUIService(AudioService.KARAOKE_SERVICE);
            } catch (RemoteException e) {
                Log.e(AudioService.TAG, e.toString());
            }
            if (binder != null) {
                AudioService.this.mXMic = IKaraoke.Stub.asInterface(binder);
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            AudioService.this.mXUIService = null;
            AudioService.this.mConnectionState = 0;
        }
    };
    private final IUidObserver mUidObserver = new IUidObserver.Stub() { // from class: com.android.server.audio.AudioService.3
        public void onUidStateChanged(int uid, int procState, long procStateSeq) {
        }

        public void onUidGone(int uid, boolean disabled) {
            disableAudioForUid(false, uid);
        }

        public void onUidActive(int uid) throws RemoteException {
        }

        public void onUidIdle(int uid, boolean disabled) {
        }

        public void onUidCachedChanged(int uid, boolean cached) {
            disableAudioForUid(cached, uid);
        }

        private void disableAudioForUid(boolean disable, int uid) {
            AudioService audioService = AudioService.this;
            audioService.queueMsgUnderWakeLock(audioService.mAudioHandler, 100, disable ? 1 : 0, uid, null, 0);
        }
    };
    private AtomicBoolean mVoiceActive = new AtomicBoolean(false);
    private final IPlaybackConfigDispatcher mVoiceActivityMonitor = new IPlaybackConfigDispatcher.Stub() { // from class: com.android.server.audio.AudioService.5
        public void dispatchPlaybackConfigChange(List<AudioPlaybackConfiguration> configs, boolean flush) {
            AudioService.sendMsg(AudioService.this.mAudioHandler, 29, 0, 0, 0, configs, 0);
        }
    };
    private int mRmtSbmxFullVolRefCount = 0;
    private ArrayList<RmtSbmxFullVolDeathHandler> mRmtSbmxFullVolDeathHandlers = new ArrayList<>();
    private final Object mSafeMediaVolumeStateLock = new Object();
    private int mMcc = 0;
    final int mSafeMediaVolumeDevices = 67108876;
    private final Object mHdmiClientLock = new Object();
    private boolean mHdmiSystemAudioSupported = false;
    private MyDisplayStatusCallback mHdmiDisplayStatusCallback = new MyDisplayStatusCallback();
    private final AudioEventLogger mModeLogger = new AudioEventLogger(20, "phone state (logged after successfull call to AudioSystem.setPhoneState(int))");
    private final AudioEventLogger mDynPolicyLogger = new AudioEventLogger(10, "dynamic policy events (logged when command received by AudioService)");
    private final Object mExtVolumeControllerLock = new Object();
    private final AudioSystem.DynamicPolicyCallback mDynPolicyCallback = new AudioSystem.DynamicPolicyCallback() { // from class: com.android.server.audio.AudioService.7
        public void onDynamicPolicyMixStateUpdate(String regId, int state) {
            if (!TextUtils.isEmpty(regId)) {
                AudioService.sendMsg(AudioService.this.mAudioHandler, 19, 2, state, 0, regId, 0);
            }
        }
    };
    private HashMap<IBinder, AsdProxy> mAudioServerStateListeners = new HashMap<>();
    ISoundResourceListener mSoundResourceListener = new ISoundResourceListener.Stub() { // from class: com.android.server.audio.AudioService.9
        public void onResourceEvent(int resId, int event) throws RemoteException {
            Log.i(AudioService.TAG, "onResourceEvent " + resId + " " + event);
            if (event == 1) {
                AudioService.this.unloadSoundEffects();
                AudioService.this.loadSoundEffects();
            }
        }
    };
    remoteConnect.RemoteConnectListerer mRemoteConnectListerer = new remoteConnect.RemoteConnectListerer() { // from class: com.android.server.audio.AudioService.10
        @Override // com.xiaopeng.xui.xuiaudio.utils.remoteConnect.RemoteConnectListerer
        public void onCarServiceConnected() {
        }

        @Override // com.xiaopeng.xui.xuiaudio.utils.remoteConnect.RemoteConnectListerer
        public void onXuiServiceConnected() {
            if (AudioService.this.mRemoteConnect != null) {
                try {
                    Log.e(AudioService.TAG, "onXuiServiceConnected !");
                    AudioService.this.mSoundResource = AudioService.this.mRemoteConnect.getSoundResource();
                    AudioService.this.mSoundResource.registerListener(AudioService.this.mSoundResourceListener);
                } catch (Exception e) {
                    Log.e(AudioService.TAG, "onXuiServiceConnected  e:" + e);
                }
            }
        }
    };
    private final Map<IBinder, IAudioEventListener> mListenersMap = new HashMap();
    private final Map<IBinder, AudioDeathRecipient> mDeathRecipientMap = new HashMap();
    IAudioEventListener iAudioListener = new IAudioEventListener.Stub() { // from class: com.android.server.audio.AudioService.11
        public void AudioEventChangeCallBack(int event, int value) {
            Log.i(AudioService.TAG, "IAudioEventListener AudioEventChangeCallBack:" + event + " " + value);
            if (AudioService.mkHandler != null) {
                Message m = AudioService.mkHandler.obtainMessage(event, Integer.valueOf(value));
                AudioService.mkHandler.sendMessage(m);
            }
        }

        public void onError(int errorCode, int operation) {
            Log.d(AudioService.TAG, "IAudioEventListener onError:" + errorCode + " operation:" + operation);
        }
    };
    private final HashMap<IBinder, AudioPolicyProxy> mAudioPolicies = new HashMap<>();
    @GuardedBy({"mAudioPolicies"})
    private int mAudioPolicyCounter = 0;
    private final UserManagerInternal mUserManagerInternal = (UserManagerInternal) LocalServices.getService(UserManagerInternal.class);
    private final ActivityManagerInternal mActivityManagerInternal = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    public @interface BtProfileConnectionState {
    }

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    public @interface ConnectionState {
    }

    static /* synthetic */ int access$9708(AudioService x0) {
        int i = x0.mAudioPolicyCounter;
        x0.mAudioPolicyCounter = i + 1;
        return i;
    }

    private boolean isPlatformVoice() {
        return this.mPlatformType == 1;
    }

    boolean isPlatformTelevision() {
        return this.mPlatformType == 2;
    }

    boolean isPlatformAutomotive() {
        return this.mContext.getPackageManager().hasSystemFeature("android.hardware.type.automotive");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getVssVolumeForDevice(int stream, int device) {
        return this.mStreamStates[stream].getIndex(device);
    }

    private void BindXUIService() {
        if (this.mXUIService == null && this.mContext != null) {
            Intent intent = new Intent();
            intent.setPackage(XUI_SERVICE_PACKAGE);
            intent.setAction(XUI_SERVICE_INTERFACE_NAME);
            Log.i(TAG, "systemReady() bind XUIService");
            this.mContext.bindService(intent, this.mXMicConnectionListener, 1);
        }
    }

    public static String makeAlsaAddressString(int card, int device) {
        return "card=" + card + ";device=" + device + ";";
    }

    /* loaded from: classes.dex */
    public static final class Lifecycle extends SystemService {
        private AudioService mService;

        public Lifecycle(Context context) {
            super(context);
            this.mService = new AudioService(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            publishBinderService("audio", this.mService);
        }

        @Override // com.android.server.SystemService
        public void onBootPhase(int phase) {
            if (phase == 550) {
                this.mService.systemReady();
            }
        }
    }

    public AudioService(Context context) {
        int i;
        this.mSystemSoundOn = true;
        this.mContext = context;
        this.mContentResolver = context.getContentResolver();
        this.mAppOps = (AppOpsManager) context.getSystemService("appops");
        this.mPlatformType = AudioSystem.getPlatformType(context);
        this.mIsSingleVolume = AudioSystem.isSingleVolume(context);
        PowerManager pm = (PowerManager) context.getSystemService("power");
        this.mAudioEventWakeLock = pm.newWakeLock(1, "handleAudioEvent");
        this.mVibrator = (Vibrator) context.getSystemService("vibrator");
        Vibrator vibrator = this.mVibrator;
        this.mHasVibrator = vibrator == null ? false : vibrator.hasVibrator();
        if (AudioProductStrategy.getAudioProductStrategies().size() > 0) {
            int numStreamTypes = AudioSystem.getNumStreamTypes();
            for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                AudioAttributes attr = AudioProductStrategy.getAudioAttributesForStrategyWithLegacyStreamType(streamType);
                int maxVolume = AudioSystem.getMaxVolumeIndexForAttributes(attr);
                if (maxVolume != -1) {
                    MAX_STREAM_VOLUME[streamType] = maxVolume;
                }
                int minVolume = AudioSystem.getMinVolumeIndexForAttributes(attr);
                if (minVolume != -1) {
                    MIN_STREAM_VOLUME[streamType] = minVolume;
                }
            }
        }
        int maxCallVolume = SystemProperties.getInt("ro.config.vc_call_vol_steps", -1);
        if (maxCallVolume != -1) {
            MAX_STREAM_VOLUME[0] = maxCallVolume;
        }
        int defaultCallVolume = SystemProperties.getInt("ro.config.vc_call_vol_default", -1);
        if (defaultCallVolume == -1 || defaultCallVolume > MAX_STREAM_VOLUME[0] || defaultCallVolume < MIN_STREAM_VOLUME[0]) {
            AudioSystem.DEFAULT_STREAM_VOLUME[0] = (maxCallVolume * 3) / 4;
        } else {
            AudioSystem.DEFAULT_STREAM_VOLUME[0] = defaultCallVolume;
        }
        int maxMusicVolume = SystemProperties.getInt("ro.config.media_vol_steps", -1);
        if (maxMusicVolume != -1) {
            MAX_STREAM_VOLUME[3] = maxMusicVolume;
        }
        int defaultMusicVolume = SystemProperties.getInt("ro.config.media_vol_default", -1);
        if (defaultMusicVolume != -1 && defaultMusicVolume <= MAX_STREAM_VOLUME[3] && defaultMusicVolume >= MIN_STREAM_VOLUME[3]) {
            AudioSystem.DEFAULT_STREAM_VOLUME[3] = defaultMusicVolume;
        } else if (isPlatformTelevision()) {
            AudioSystem.DEFAULT_STREAM_VOLUME[3] = MAX_STREAM_VOLUME[3] / 4;
        } else {
            AudioSystem.DEFAULT_STREAM_VOLUME[3] = MAX_STREAM_VOLUME[3] / 3;
        }
        int maxAlarmVolume = SystemProperties.getInt("ro.config.alarm_vol_steps", -1);
        if (maxAlarmVolume != -1) {
            MAX_STREAM_VOLUME[4] = maxAlarmVolume;
        }
        int defaultAlarmVolume = SystemProperties.getInt("ro.config.alarm_vol_default", -1);
        if (defaultAlarmVolume == -1 || defaultAlarmVolume > MAX_STREAM_VOLUME[4]) {
            AudioSystem.DEFAULT_STREAM_VOLUME[4] = (MAX_STREAM_VOLUME[4] * 6) / 7;
        } else {
            AudioSystem.DEFAULT_STREAM_VOLUME[4] = defaultAlarmVolume;
        }
        int maxSystemVolume = SystemProperties.getInt("ro.config.system_vol_steps", -1);
        if (maxSystemVolume != -1) {
            MAX_STREAM_VOLUME[1] = maxSystemVolume;
        }
        int defaultSystemVolume = SystemProperties.getInt("ro.config.system_vol_default", -1);
        if (defaultSystemVolume == -1 || defaultSystemVolume > MAX_STREAM_VOLUME[1]) {
            AudioSystem.DEFAULT_STREAM_VOLUME[1] = MAX_STREAM_VOLUME[1];
        } else {
            AudioSystem.DEFAULT_STREAM_VOLUME[1] = defaultSystemVolume;
        }
        sSoundEffectVolumeDb = context.getResources().getInteger(17694894);
        createAudioSystemThread();
        AudioSystem.setErrorCallback(this.mAudioSystemCallback);
        boolean cameraSoundForced = readCameraSoundForced();
        this.mCameraSoundForced = new Boolean(cameraSoundForced).booleanValue();
        AudioHandler audioHandler = this.mAudioHandler;
        if (!cameraSoundForced) {
            i = 0;
        } else {
            i = 11;
        }
        sendMsg(audioHandler, 8, 2, 4, i, new String("AudioService ctor"), 0);
        this.mSafeMediaVolumeState = Settings.Global.getInt(this.mContentResolver, "audio_safe_volume_state", 0);
        this.mSafeMediaVolumeIndex = this.mContext.getResources().getInteger(17694878) * 10;
        this.mUseFixedVolume = this.mContext.getResources().getBoolean(17891563);
        this.mDeviceBroker = new AudioDeviceBroker(this.mContext, this);
        updateStreamVolumeAlias(false, TAG);
        readPersistedSettings();
        readUserRestrictions();
        this.mSettingsObserver = new SettingsObserver();
        createStreamStates();
        this.mSafeUsbMediaVolumeIndex = getSafeUsbMediaVolumeIndex();
        this.mPlaybackMonitor = new PlaybackActivityMonitor(context, MAX_STREAM_VOLUME[4]);
        this.mMediaFocusControl = new MediaFocusControl(this.mContext, this.mPlaybackMonitor);
        this.mRecordMonitor = new RecordingActivityMonitor(this.mContext);
        readAndSetLowRamDevice();
        this.mRingerAndZenModeMutedStreams = 0;
        setRingerModeInt(getRingerModeInternal(), false);
        IntentFilter intentFilter = new IntentFilter("android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED");
        intentFilter.addAction("android.bluetooth.headset.profile.action.ACTIVE_DEVICE_CHANGED");
        intentFilter.addAction("android.intent.action.DOCK_EVENT");
        intentFilter.addAction("android.intent.action.SCREEN_ON");
        intentFilter.addAction("android.intent.action.SCREEN_OFF");
        intentFilter.addAction("android.intent.action.USER_SWITCHED");
        intentFilter.addAction("android.intent.action.USER_BACKGROUND");
        intentFilter.addAction("android.intent.action.USER_FOREGROUND");
        intentFilter.addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED");
        intentFilter.addAction("android.bluetooth.adapter.action.STATE_CHANGED");
        intentFilter.addAction("android.intent.action.PACKAGES_SUSPENDED");
        intentFilter.addAction("android.intent.action.CONFIGURATION_CHANGED");
        this.mMonitorRotation = SystemProperties.getBoolean("ro.audio.monitorRotation", false);
        if (this.mMonitorRotation) {
            RotationHelper.init(this.mContext, this.mAudioHandler);
        }
        intentFilter.addAction("android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION");
        intentFilter.addAction("android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION");
        context.registerReceiverAsUser(this.mReceiver, UserHandle.ALL, intentFilter, null, null);
        LocalServices.addService(AudioManagerInternal.class, new AudioServiceInternal());
        this.mUserManagerInternal.addUserRestrictionsListener(this.mUserRestrictionsListener);
        this.mRecordMonitor.initMonitor();
        float[] preScale = {this.mContext.getResources().getFraction(18022403, 1, 1), this.mContext.getResources().getFraction(18022404, 1, 1), this.mContext.getResources().getFraction(18022405, 1, 1)};
        for (int i2 = 0; i2 < preScale.length; i2++) {
            if (0.0f <= preScale[i2] && preScale[i2] <= 1.0f) {
                this.mPrescaleAbsoluteVolume[i2] = preScale[i2];
            }
        }
        this.mSystemSoundOn = SystemProperties.getBoolean(SYSTEM_SOUND_CONFIG, true);
        if (FeatureOption.FO_AVAS_SUPPORT_TYPE == 1) {
            avasStreamEnable = Settings.System.getInt(this.mContext.getContentResolver(), AVAS_STREAM_ENABLE, 0) == 1;
            this.mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(AVAS_STREAM_ENABLE), true, new ContentObserver(new Handler()) { // from class: com.android.server.audio.AudioService.4
                @Override // android.database.ContentObserver
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);
                    boolean unused = AudioService.avasStreamEnable = Settings.System.getInt(AudioService.this.mContext.getContentResolver(), AudioService.AVAS_STREAM_ENABLE, 0) == 1;
                    Log.d(AudioService.TAG, "avasStreamEnable change to " + AudioService.avasStreamEnable);
                }
            });
        }
        mFixedThreadExecutor = Executors.newFixedThreadPool(10);
    }

    public void systemReady() {
        sendMsg(this.mAudioHandler, 16, 2, 0, 0, null, 0);
    }

    public void onSystemReady() {
        this.mSystemReady = true;
        scheduleLoadSoundEffects();
        this.mDeviceBroker.onSystemReady();
        if (this.mContext.getPackageManager().hasSystemFeature("android.hardware.hdmi.cec")) {
            synchronized (this.mHdmiClientLock) {
                this.mHdmiManager = (HdmiControlManager) this.mContext.getSystemService(HdmiControlManager.class);
                this.mHdmiTvClient = this.mHdmiManager.getTvClient();
                if (this.mHdmiTvClient != null) {
                    this.mFixedVolumeDevices &= -2883587;
                }
                this.mHdmiPlaybackClient = this.mHdmiManager.getPlaybackClient();
                if (this.mHdmiPlaybackClient != null) {
                    this.mFixedVolumeDevices &= -1025;
                    this.mFullVolumeDevices |= 1024;
                }
                this.mHdmiCecSink = false;
                this.mHdmiAudioSystemClient = this.mHdmiManager.getAudioSystemClient();
            }
        }
        this.mNm = (NotificationManager) this.mContext.getSystemService("notification");
        sendMsg(this.mAudioHandler, 13, 0, 0, 0, TAG, SystemProperties.getBoolean("audio.safemedia.bypass", false) ? 0 : SAFE_VOLUME_CONFIGURE_TIMEOUT_MS);
        initA11yMonitoring();
        this.mRoleObserver = new RoleObserver();
        this.mRoleObserver.register();
        onIndicateSystemReady();
        if (newPolicyOpen) {
            this.mXuiAudioPolicyTrigger = new xuiAudioPolicyTrigger(this.mContext);
            this.mMediaFocusControl.setXuiAudio(this.mXuiAudioPolicyTrigger);
            connectXUI();
            return;
        }
        this.mXpAudio = new xpAudio(this.mContext);
        this.mMediaFocusControl.setXpAudio(this.mXpAudio);
        this.mXpAudioPloicy = XpAudioPolicy.getInstance();
    }

    public IXpVehicle getXpVehicle() {
        Log.d(TAG, "getXpVehicle()");
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.getXpVehicle();
            }
            return null;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.getXpVehicle();
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class RoleObserver implements OnRoleHoldersChangedListener {
        private final Executor mExecutor;
        private RoleManager mRm;

        RoleObserver() {
            this.mExecutor = AudioService.this.mContext.getMainExecutor();
        }

        public void register() {
            this.mRm = (RoleManager) AudioService.this.mContext.getSystemService("role");
            RoleManager roleManager = this.mRm;
            if (roleManager != null) {
                roleManager.addOnRoleHoldersChangedListenerAsUser(this.mExecutor, this, UserHandle.ALL);
                AudioService.this.updateAssistantUId(true);
            }
        }

        public void onRoleHoldersChanged(String roleName, UserHandle user) {
            if ("android.app.role.ASSISTANT".equals(roleName)) {
                AudioService.this.updateAssistantUId(false);
            }
        }

        public String getAssistantRoleHolder() {
            RoleManager roleManager = this.mRm;
            if (roleManager == null) {
                return "";
            }
            List<String> assistants = roleManager.getRoleHolders("android.app.role.ASSISTANT");
            String assitantPackage = assistants.size() == 0 ? "" : assistants.get(0);
            return assitantPackage;
        }
    }

    void onIndicateSystemReady() {
        if (AudioSystem.systemReady() == 0) {
            return;
        }
        sendMsg(this.mAudioHandler, 20, 0, 0, 0, null, 1000);
    }

    public void onAudioServerDied() {
        int forSys;
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (!this.mSystemReady || AudioSystem.checkAudioFlinger() != 0) {
            Log.e(TAG, "Audioserver died.");
            sendMsg(this.mAudioHandler, 4, 1, 0, 0, null, 500);
            return;
        }
        Log.e(TAG, "Audioserver started.");
        AudioSystem.setParameters("restarting=true");
        readAndSetLowRamDevice();
        this.mDeviceBroker.onAudioServerDied();
        Log.i(TAG, "onAudioServerDied  stage 2");
        if (AudioSystem.setPhoneState(this.mMode) == 0) {
            this.mModeLogger.log(new AudioEventLogger.StringEvent("onAudioServerDied causes setPhoneState(" + AudioSystem.modeToString(this.mMode) + ")"));
        }
        synchronized (this.mSettingsLock) {
            forSys = this.mCameraSoundForced ? 11 : 0;
        }
        this.mDeviceBroker.setForceUse_Async(4, forSys, "onAudioServerDied");
        Log.i(TAG, "onAudioServerDied  stage3");
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
            VolumeStreamState streamState = this.mStreamStates[streamType];
            AudioSystem.initStreamVolume(streamType, streamState.mIndexMin / 10, streamState.mIndexMax / 10);
            streamState.applyAllVolumes();
        }
        updateMasterMono(this.mContentResolver);
        updateMasterBalance(this.mContentResolver);
        setRingerModeInt(getRingerModeInternal(), false);
        if (this.mMonitorRotation) {
            RotationHelper.updateOrientation();
        }
        Log.i(TAG, "onAudioServerDied  stage4");
        synchronized (this.mSettingsLock) {
            int forDock = this.mDockAudioMediaEnabled ? 8 : 0;
            this.mDeviceBroker.setForceUse_Async(3, forDock, "onAudioServerDied");
            sendEncodedSurroundMode(this.mContentResolver, "onAudioServerDied");
            sendEnabledSurroundFormats(this.mContentResolver, true);
            updateAssistantUId(true);
            updateRttEanbled(this.mContentResolver);
        }
        synchronized (this.mAccessibilityServiceUidsLock) {
            AudioSystem.setA11yServicesUids(this.mAccessibilityServiceUids);
        }
        Log.i(TAG, "onAudioServerDied  stage5");
        synchronized (this.mHdmiClientLock) {
            if (this.mHdmiManager != null && this.mHdmiTvClient != null) {
                setHdmiSystemAudioSupported(this.mHdmiSystemAudioSupported);
            }
        }
        synchronized (this.mAudioPolicies) {
            Log.d(TAG, "onAudioServerDied mAudioPolicies.values():" + this.mAudioPolicies.values());
            for (AudioPolicyProxy policy : this.mAudioPolicies.values()) {
                Log.d(TAG, "onAudioServerDied connectMixes!!!!");
                policy.connectMixes();
            }
        }
        synchronized (this.mPlaybackMonitor) {
            HashMap<Integer, Integer> allowedCapturePolicies = this.mPlaybackMonitor.getAllAllowedCapturePolicies();
            for (Map.Entry<Integer, Integer> entry : allowedCapturePolicies.entrySet()) {
                int result = AudioSystem.setAllowedCapturePolicy(entry.getKey().intValue(), AudioAttributes.capturePolicyToFlags(entry.getValue().intValue(), 0));
                if (result != 0) {
                    Log.e(TAG, "Failed to restore capture policy, uid: " + entry.getKey() + ", capture policy: " + entry.getValue() + ", result: " + result);
                    this.mPlaybackMonitor.setAllowedCapturePolicy(entry.getKey().intValue(), 1);
                }
            }
        }
        onIndicateSystemReady();
        AudioSystem.setParameters("restarting=false");
        sendMsg(this.mAudioHandler, 23, 2, 1, 0, null, 0);
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.audioServerDiedRestore();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onDispatchAudioServerStateChange(boolean state) {
        synchronized (this.mAudioServerStateListeners) {
            for (AsdProxy asdp : this.mAudioServerStateListeners.values()) {
                try {
                    asdp.callback().dispatchAudioServerStateChange(state);
                } catch (RemoteException e) {
                    Log.w(TAG, "Could not call dispatchAudioServerStateChange()", e);
                }
            }
        }
    }

    private void createAudioSystemThread() {
        this.mAudioSystemThread = new AudioSystemThread();
        this.mAudioSystemThread.start();
        waitForAudioHandlerCreation();
    }

    private void waitForAudioHandlerCreation() {
        synchronized (this) {
            while (this.mAudioHandler == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted while waiting on volume handler.");
                }
            }
        }
    }

    public List<AudioProductStrategy> getAudioProductStrategies() {
        return AudioProductStrategy.getAudioProductStrategies();
    }

    public List<AudioVolumeGroup> getAudioVolumeGroups() {
        return AudioVolumeGroup.getAudioVolumeGroups();
    }

    private void checkAllAliasStreamVolumes() {
        synchronized (this.mSettingsLock) {
            synchronized (VolumeStreamState.class) {
                int numStreamTypes = AudioSystem.getNumStreamTypes();
                for (int streamType = 0; streamType < numStreamTypes; streamType++) {
                    this.mStreamStates[streamType].setAllIndexes(this.mStreamStates[mStreamVolumeAlias[streamType]], TAG);
                    if (!this.mStreamStates[streamType].mIsMuted) {
                        this.mStreamStates[streamType].applyAllVolumes();
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postCheckVolumeCecOnHdmiConnection(int state, String caller) {
        sendMsg(this.mAudioHandler, MSG_HDMI_VOLUME_CHECK, 0, state, 0, caller, 0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onCheckVolumeCecOnHdmiConnection(int state, String caller) {
        if (state == 1) {
            if (isPlatformTelevision()) {
                checkAddAllFixedVolumeDevices(1024, caller);
                synchronized (this.mHdmiClientLock) {
                    if (this.mHdmiManager != null && this.mHdmiPlaybackClient != null) {
                        this.mHdmiCecSink = false;
                        this.mHdmiPlaybackClient.queryDisplayStatus(this.mHdmiDisplayStatusCallback);
                    }
                }
            }
            sendEnabledSurroundFormats(this.mContentResolver, true);
        } else if (isPlatformTelevision()) {
            synchronized (this.mHdmiClientLock) {
                if (this.mHdmiManager != null) {
                    this.mHdmiCecSink = false;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkAddAllFixedVolumeDevices(int device, String caller) {
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int streamType = 0; streamType < numStreamTypes; streamType++) {
            if (!this.mStreamStates[streamType].hasIndexForDevice(device)) {
                VolumeStreamState[] volumeStreamStateArr = this.mStreamStates;
                volumeStreamStateArr[streamType].setIndex(volumeStreamStateArr[mStreamVolumeAlias[streamType]].getIndex(1073741824), device, caller);
            }
            this.mStreamStates[streamType].checkFixedVolumeDevices();
        }
    }

    private void checkAllFixedVolumeDevices() {
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int streamType = 0; streamType < numStreamTypes; streamType++) {
            this.mStreamStates[streamType].checkFixedVolumeDevices();
        }
    }

    private void checkAllFixedVolumeDevices(int streamType) {
        this.mStreamStates[streamType].checkFixedVolumeDevices();
    }

    private void checkMuteAffectedStreams() {
        int i = 0;
        while (true) {
            VolumeStreamState[] volumeStreamStateArr = this.mStreamStates;
            if (i < volumeStreamStateArr.length) {
                VolumeStreamState vss = volumeStreamStateArr[i];
                if (vss.mIndexMin > 0 && vss.mStreamType != 0 && vss.mStreamType != 6) {
                    this.mMuteAffectedStreams &= ~(1 << vss.mStreamType);
                }
                i++;
            } else {
                return;
            }
        }
    }

    private void createStreamStates() {
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        VolumeStreamState[] streams = new VolumeStreamState[numStreamTypes];
        this.mStreamStates = streams;
        for (int i = 0; i < numStreamTypes; i++) {
            streams[i] = new VolumeStreamState(Settings.System.VOLUME_SETTINGS_INT[mStreamVolumeAlias[i]], i);
        }
        checkAllFixedVolumeDevices();
        checkAllAliasStreamVolumes();
        checkMuteAffectedStreams();
        updateDefaultVolumes();
    }

    private void updateDefaultVolumes() {
        for (int stream = 0; stream < this.mStreamStates.length; stream++) {
            if (stream != mStreamVolumeAlias[stream]) {
                int[] iArr = AudioSystem.DEFAULT_STREAM_VOLUME;
                int[] iArr2 = AudioSystem.DEFAULT_STREAM_VOLUME;
                int[] iArr3 = mStreamVolumeAlias;
                iArr[stream] = rescaleIndex(iArr2[iArr3[stream]], iArr3[stream], stream);
            }
        }
    }

    private void dumpStreamStates(PrintWriter pw) {
        pw.println("\nStream volumes (device: index)");
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int i = 0; i < numStreamTypes; i++) {
            pw.println("- " + AudioSystem.STREAM_NAMES[i] + ":");
            this.mStreamStates[i].dump(pw);
            pw.println("");
        }
        pw.print("\n- mute affected streams = 0x");
        pw.println(Integer.toHexString(this.mMuteAffectedStreams));
    }

    private void updateStreamVolumeAlias(boolean updateVolumes, String caller) {
        int dtmfStreamAlias;
        int dtmfStreamAlias2;
        int a11yStreamAlias = sIndependentA11yVolume ? 10 : 3;
        if (this.mIsSingleVolume) {
            mStreamVolumeAlias = this.STREAM_VOLUME_ALIAS_TELEVISION;
            dtmfStreamAlias = 3;
        } else {
            int dtmfStreamAlias3 = this.mPlatformType;
            if (dtmfStreamAlias3 == 1) {
                mStreamVolumeAlias = this.STREAM_VOLUME_ALIAS_VOICE;
                dtmfStreamAlias = 2;
            } else {
                mStreamVolumeAlias = this.STREAM_VOLUME_ALIAS_DEFAULT;
                dtmfStreamAlias = 3;
            }
        }
        if (this.mIsSingleVolume) {
            this.mRingerModeAffectedStreams = 0;
        } else if (isInCommunication()) {
            this.mRingerModeAffectedStreams &= -257;
            dtmfStreamAlias2 = 0;
            int[] iArr = mStreamVolumeAlias;
            iArr[8] = dtmfStreamAlias2;
            iArr[10] = a11yStreamAlias;
            if (!updateVolumes && this.mStreamStates != null) {
                updateDefaultVolumes();
                synchronized (this.mSettingsLock) {
                    synchronized (VolumeStreamState.class) {
                        this.mStreamStates[8].setAllIndexes(this.mStreamStates[dtmfStreamAlias2], caller);
                        this.mStreamStates[10].mVolumeIndexSettingName = Settings.System.VOLUME_SETTINGS_INT[a11yStreamAlias];
                        this.mStreamStates[10].setAllIndexes(this.mStreamStates[a11yStreamAlias], caller);
                    }
                }
                if (sIndependentA11yVolume) {
                    this.mStreamStates[10].readSettings();
                }
                setRingerModeInt(getRingerModeInternal(), false);
                sendMsg(this.mAudioHandler, 10, 2, 0, 0, this.mStreamStates[8], 0);
                sendMsg(this.mAudioHandler, 10, 2, 0, 0, this.mStreamStates[10], 0);
                return;
            }
        } else {
            this.mRingerModeAffectedStreams |= 256;
        }
        dtmfStreamAlias2 = dtmfStreamAlias;
        int[] iArr2 = mStreamVolumeAlias;
        iArr2[8] = dtmfStreamAlias2;
        iArr2[10] = a11yStreamAlias;
        if (!updateVolumes) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void readDockAudioSettings(ContentResolver cr) {
        int i = 0;
        this.mDockAudioMediaEnabled = Settings.Global.getInt(cr, "dock_audio_media_enabled", 0) == 1;
        AudioHandler audioHandler = this.mAudioHandler;
        if (this.mDockAudioMediaEnabled) {
            i = 8;
        }
        sendMsg(audioHandler, 8, 2, 3, i, new String("readDockAudioSettings"), 0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateMasterMono(ContentResolver cr) {
        boolean masterMono = Settings.System.getIntForUser(cr, "master_mono", 0, -2) == 1;
        Log.d(TAG, String.format("Master mono %b", Boolean.valueOf(masterMono)));
        AudioSystem.setMasterMono(masterMono);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateMasterBalance(ContentResolver cr) {
        float masterBalance = Settings.System.getFloatForUser(cr, "master_balance", 0.0f, -2);
        Log.d(TAG, String.format("Master balance %f", Float.valueOf(masterBalance)));
        if (AudioSystem.setMasterBalance(masterBalance) != 0) {
            Log.e(TAG, String.format("setMasterBalance failed for %f", Float.valueOf(masterBalance)));
        }
    }

    private void sendEncodedSurroundMode(ContentResolver cr, String eventSource) {
        int encodedSurroundMode = Settings.Global.getInt(cr, "encoded_surround_output", 0);
        sendEncodedSurroundMode(encodedSurroundMode, eventSource);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendEncodedSurroundMode(int encodedSurroundMode, String eventSource) {
        int forceSetting = 16;
        if (encodedSurroundMode == 0) {
            forceSetting = 0;
        } else if (encodedSurroundMode == 1) {
            forceSetting = 13;
        } else if (encodedSurroundMode == 2) {
            forceSetting = 14;
        } else if (encodedSurroundMode == 3) {
            forceSetting = 15;
        } else {
            Log.e(TAG, "updateSurroundSoundSettings: illegal value " + encodedSurroundMode);
        }
        if (forceSetting != 16) {
            this.mDeviceBroker.setForceUse_Async(6, forceSetting, eventSource);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendEnabledSurroundFormats(ContentResolver cr, boolean forceUpdate) {
        String enabledSurroundFormats;
        if (this.mEncodedSurroundMode != 3) {
            return;
        }
        String enabledSurroundFormats2 = Settings.Global.getString(cr, "encoded_surround_output_enabled_formats");
        if (enabledSurroundFormats2 != null) {
            enabledSurroundFormats = enabledSurroundFormats2;
        } else {
            enabledSurroundFormats = "";
        }
        if (!forceUpdate && TextUtils.equals(enabledSurroundFormats, this.mEnabledSurroundFormats)) {
            return;
        }
        this.mEnabledSurroundFormats = enabledSurroundFormats;
        String[] surroundFormats = TextUtils.split(enabledSurroundFormats, ",");
        ArrayList<Integer> formats = new ArrayList<>();
        for (String format : surroundFormats) {
            try {
                int audioFormat = Integer.valueOf(format).intValue();
                boolean isSurroundFormat = false;
                int[] iArr = AudioFormat.SURROUND_SOUND_ENCODING;
                int length = iArr.length;
                int i = 0;
                while (true) {
                    if (i >= length) {
                        break;
                    }
                    int sf = iArr[i];
                    if (sf != audioFormat) {
                        i++;
                    } else {
                        isSurroundFormat = true;
                        break;
                    }
                }
                if (isSurroundFormat && !formats.contains(Integer.valueOf(audioFormat))) {
                    formats.add(Integer.valueOf(audioFormat));
                }
            } catch (Exception e) {
                Log.e(TAG, "Invalid enabled surround format:" + format);
            }
        }
        Settings.Global.putString(this.mContext.getContentResolver(), "encoded_surround_output_enabled_formats", TextUtils.join(",", formats));
        sendMsg(this.mAudioHandler, 24, 2, 0, 0, formats, 0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onEnableSurroundFormats(ArrayList<Integer> enabledSurroundFormats) {
        int[] iArr;
        for (int surroundFormat : AudioFormat.SURROUND_SOUND_ENCODING) {
            boolean enabled = enabledSurroundFormats.contains(Integer.valueOf(surroundFormat));
            int ret = AudioSystem.setSurroundFormatEnabled(surroundFormat, enabled);
            Log.i(TAG, "enable surround format:" + surroundFormat + " " + enabled + " " + ret);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mSettingsLock"})
    public void updateAssistantUId(boolean forceUpdate) {
        int assistantUid = 0;
        String packageName = "";
        RoleObserver roleObserver = this.mRoleObserver;
        if (roleObserver != null) {
            packageName = roleObserver.getAssistantRoleHolder();
        }
        if (TextUtils.isEmpty(packageName)) {
            String assistantName = Settings.Secure.getStringForUser(this.mContentResolver, "voice_interaction_service", -2);
            if (TextUtils.isEmpty(assistantName)) {
                assistantName = Settings.Secure.getStringForUser(this.mContentResolver, "assistant", -2);
            }
            if (!TextUtils.isEmpty(assistantName)) {
                ComponentName componentName = ComponentName.unflattenFromString(assistantName);
                if (componentName == null) {
                    Slog.w(TAG, "Invalid service name for voice_interaction_service: " + assistantName);
                    return;
                }
                packageName = componentName.getPackageName();
            }
        }
        if (!TextUtils.isEmpty(packageName)) {
            PackageManager pm = this.mContext.getPackageManager();
            if (pm.checkPermission("android.permission.CAPTURE_AUDIO_HOTWORD", packageName) == 0) {
                try {
                    assistantUid = pm.getPackageUid(packageName, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.e(TAG, "updateAssistantUId() could not find UID for package: " + packageName);
                }
            }
        }
        if (assistantUid != this.mAssistantUid || forceUpdate) {
            AudioSystem.setAssistantUid(assistantUid);
            this.mAssistantUid = assistantUid;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateRttEanbled(ContentResolver cr) {
        boolean rttEnabled = Settings.Secure.getIntForUser(cr, "rtt_calling_mode", 0, -2) != 0;
        AudioSystem.setRttEnabled(rttEnabled);
    }

    private void readPersistedSettings() {
        int i;
        ContentResolver cr = this.mContentResolver;
        int i2 = 2;
        int ringerModeFromSettings = Settings.Global.getInt(cr, "mode_ringer", 2);
        int ringerMode = ringerModeFromSettings;
        if (!isValidRingerMode(ringerMode)) {
            ringerMode = 2;
        }
        if (ringerMode == 1 && !this.mHasVibrator) {
            ringerMode = 0;
        }
        if (ringerMode != ringerModeFromSettings) {
            Settings.Global.putInt(cr, "mode_ringer", ringerMode);
        }
        ringerMode = (this.mUseFixedVolume || this.mIsSingleVolume) ? 2 : 2;
        synchronized (this.mSettingsLock) {
            this.mRingerMode = ringerMode;
            if (this.mRingerModeExternal == -1) {
                this.mRingerModeExternal = this.mRingerMode;
            }
            if (this.mHasVibrator) {
                i = 2;
            } else {
                i = 0;
            }
            this.mVibrateSetting = AudioSystem.getValueForVibrateSetting(0, 1, i);
            int i3 = this.mVibrateSetting;
            if (!this.mHasVibrator) {
                i2 = 0;
            }
            this.mVibrateSetting = AudioSystem.getValueForVibrateSetting(i3, 0, i2);
            updateRingerAndZenModeAffectedStreams();
            readDockAudioSettings(cr);
            sendEncodedSurroundMode(cr, "readPersistedSettings");
            sendEnabledSurroundFormats(cr, true);
            updateAssistantUId(true);
            updateRttEanbled(cr);
        }
        this.mMuteAffectedStreams = Settings.System.getIntForUser(cr, "mute_streams_affected", 111, -2);
        updateMasterMono(cr);
        updateMasterBalance(cr);
        broadcastRingerMode("android.media.RINGER_MODE_CHANGED", this.mRingerModeExternal);
        broadcastRingerMode("android.media.INTERNAL_RINGER_MODE_CHANGED_ACTION", this.mRingerMode);
        broadcastVibrateSetting(0);
        broadcastVibrateSetting(1);
        this.mVolumeController.loadSettings(cr);
    }

    private void readUserRestrictions() {
        int currentUser = getCurrentUserId();
        boolean masterMute = this.mUserManagerInternal.getUserRestriction(currentUser, "disallow_unmute_device") || this.mUserManagerInternal.getUserRestriction(currentUser, "no_adjust_volume");
        if (this.mUseFixedVolume) {
            masterMute = false;
            AudioSystem.setMasterVolume(1.0f);
        }
        Log.d(TAG, String.format("Master mute %s, user=%d", Boolean.valueOf(masterMute), Integer.valueOf(currentUser)));
        setSystemAudioMute(masterMute);
        AudioSystem.setMasterMute(masterMute);
        broadcastMasterMuteStatus(masterMute);
        AudioSystem.muteMicrophone(SystemProperties.getBoolean(SYSTEM_MIC_MUTE, false));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int rescaleIndex(int index, int srcStream, int dstStream) {
        int srcRange = this.mStreamStates[srcStream].getMaxIndex() - this.mStreamStates[srcStream].getMinIndex();
        int dstRange = this.mStreamStates[dstStream].getMaxIndex() - this.mStreamStates[dstStream].getMinIndex();
        if (srcRange == 0) {
            Log.e(TAG, "rescaleIndex : index range should not be zero");
            return this.mStreamStates[dstStream].getMinIndex();
        }
        return this.mStreamStates[dstStream].getMinIndex() + ((((index - this.mStreamStates[srcStream].getMinIndex()) * dstRange) + (srcRange / 2)) / srcRange);
    }

    private boolean allowAdjustVolume(String callingPackage) {
        Binder.getCallingUid();
        if (ActivityInfoManager.isSystemApplication(callingPackage)) {
            return true;
        }
        try {
            xpPackageInfo info = xpPackageManagerService.get(this.mContext).getXpPackageInfo(callingPackage);
            if (info == null) {
                return false;
            }
            boolean adjustVolume = info.adjustVolumeValue == 1;
            return adjustVolume;
        } catch (Exception e) {
            return false;
        }
    }

    public void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags, String callingPackage, String caller) {
        IAudioPolicyCallback extVolCtlr;
        synchronized (this.mExtVolumeControllerLock) {
            extVolCtlr = this.mExtVolumeController;
        }
        Log.d(TAG, "adjustSuggestedStreamVolume  direction:" + direction + "  suggestedStreamType:" + suggestedStreamType + " " + flags + " " + callingPackage + " " + caller);
        if (extVolCtlr != null && this.mXpAudio == null && this.mXuiAudioPolicyTrigger == null) {
            sendMsg(this.mAudioHandler, 22, 2, direction, 0, extVolCtlr, 0);
        } else {
            adjustSuggestedStreamVolume(direction, suggestedStreamType, flags, callingPackage, caller, Binder.getCallingUid());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags, String callingPackage, String caller, int uid) {
        int maybeActiveStreamType;
        boolean activeForReal;
        int flags2;
        int direction2;
        int flags3;
        Log.d(TAG, "adjustSuggestedStreamVolume() stream=" + suggestedStreamType + ", flags=" + flags + ", caller=" + caller + ", volControlStream=" + this.mVolumeControlStream + ", userSelect=" + this.mUserSelectedVolumeControlStream);
        if (direction != 0) {
            sVolumeLogger.log(new AudioServiceEvents.VolumeEvent(0, suggestedStreamType, direction, flags, callingPackage + SliceClientPermissions.SliceAuthority.DELIMITER + caller + " uid:" + uid));
        }
        synchronized (this.mForceControlStreamLock) {
            if (this.mUserSelectedVolumeControlStream) {
                maybeActiveStreamType = this.mVolumeControlStream;
            } else {
                maybeActiveStreamType = getActiveStreamType(suggestedStreamType);
                if (maybeActiveStreamType != 2 && maybeActiveStreamType != 5) {
                    activeForReal = AudioSystem.isStreamActive(maybeActiveStreamType, 0);
                    if (!activeForReal && this.mVolumeControlStream != -1) {
                        maybeActiveStreamType = this.mVolumeControlStream;
                    }
                }
                activeForReal = wasStreamActiveRecently(maybeActiveStreamType, 0);
                if (!activeForReal) {
                    maybeActiveStreamType = this.mVolumeControlStream;
                }
            }
        }
        boolean isMute = isMuteAdjust(direction);
        ensureValidStreamType(maybeActiveStreamType);
        int resolvedStream = mStreamVolumeAlias[maybeActiveStreamType];
        if ((flags & 4) != 0 && resolvedStream != 2) {
            flags2 = flags & (-5);
        } else {
            flags2 = flags;
        }
        if (this.mVolumeController.suppressAdjustment(resolvedStream, flags2, isMute) && !this.mIsSingleVolume) {
            Log.d(TAG, "Volume controller suppressed adjustment");
            flags3 = flags2 & (-5) & (-17);
            direction2 = 0;
        } else {
            direction2 = direction;
            flags3 = flags2;
        }
        adjustStreamVolume(maybeActiveStreamType, direction2, flags3, callingPackage, caller, uid);
    }

    public void adjustStreamVolume(int streamType, int direction, int flags, String callingPackage) {
        if (streamType == 10 && !canChangeAccessibilityVolume()) {
            Log.w(TAG, "Trying to call adjustStreamVolume() for a11y withoutCHANGE_ACCESSIBILITY_VOLUME / callingPackage=" + callingPackage);
        } else if (!allowAdjustVolume(callingPackage)) {
            Log.d(TAG, "do not allowed to adjustStreamVolume(stream=" + streamType + ", direction=" + direction + ", calling=" + callingPackage + ")");
        } else {
            sVolumeLogger.log(new AudioServiceEvents.VolumeEvent(1, streamType, direction, flags, callingPackage));
            adjustStreamVolume(streamType, direction, flags, callingPackage, callingPackage, Binder.getCallingUid());
        }
    }

    public static void showToast(Context context, String text, int duration, int sharedId) {
        try {
            Intent intent = new Intent("com.xiaopeng.xui.intent.action.TOAST");
            intent.putExtra("text", text);
            intent.putExtra("duration", duration);
            intent.putExtra("sharedId", sharedId);
            intent.putExtra("caller", PackageManagerService.PLATFORM_PACKAGE_NAME);
            intent.addFlags(20971552);
            context.sendBroadcastAsUser(intent, UserHandle.ALL);
        } catch (Exception e) {
        }
    }

    protected void adjustStreamVolume(int streamType, int direction, int flags, String callingPackage, String caller, int flags2) {
        int flags22;
        int step;
        int aliasIndex;
        int device;
        VolumeStreamState streamState;
        int streamTypeAlias;
        boolean isMuteAdjust;
        int i;
        int flags3;
        boolean adjustVolume;
        VolumeStreamState streamState2;
        int i2;
        boolean state;
        int step2;
        int streamType2 = streamType;
        if (this.mUseFixedVolume) {
            if (avasStreamEnable && streamType2 == 3) {
                if (direction == 101 || direction == -100 || direction == 100) {
                    if (direction == 101 && callingPackage != null) {
                        String text = this.mContext.getText(17041168).toString();
                        showToast(this.mContext, text, 0, 0);
                        return;
                    }
                } else {
                    Log.d(TAG, "adjustStreamVolume() change to STREAM_AVAS");
                    streamType2 = 11;
                }
            }
            Log.d(TAG, "adjustStreamVolume() stream=" + streamType2 + ", dir=" + direction + ", flags=" + flags + ", caller=" + caller + " avasStreamEnable:" + avasStreamEnable);
            if (newPolicyOpen) {
                xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
                if (xuiaudiopolicytrigger != null) {
                    xuiaudiopolicytrigger.adjustStreamVolume(streamType2, direction, flags, callingPackage);
                    return;
                }
                return;
            }
            xpAudio xpaudio = this.mXpAudio;
            if (xpaudio != null) {
                xpaudio.adjustStreamVolume(streamType2, direction, flags, callingPackage);
                return;
            }
            return;
        }
        Log.d(TAG, "adjustStreamVolume() stream=" + streamType2 + ", dir=" + direction + ", flags=" + flags + ", caller=" + caller);
        ensureValidDirection(direction);
        ensureValidStreamType(streamType);
        boolean isMuteAdjust2 = isMuteAdjust(direction);
        if (isMuteAdjust2 && !isStreamAffectedByMute(streamType)) {
            return;
        }
        if (isMuteAdjust2 && ((streamType2 == 0 || streamType2 == 6) && this.mContext.checkCallingOrSelfPermission("android.permission.MODIFY_PHONE_STATE") != 0)) {
            Log.w(TAG, "MODIFY_PHONE_STATE Permission Denial: adjustStreamVolume from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
            return;
        }
        int streamTypeAlias2 = mStreamVolumeAlias[streamType2];
        VolumeStreamState streamState3 = this.mStreamStates[streamTypeAlias2];
        int device2 = getDeviceForStream(streamTypeAlias2);
        int aliasIndex2 = streamState3.getIndex(device2);
        boolean adjustVolume2 = true;
        if ((device2 & 896) == 0 && (flags & 64) != 0) {
            return;
        }
        flags22 = flags == 1000 ? UserHandle.getUid(getCurrentUserId(), UserHandle.getAppId(flags22)) : flags;
        if (this.mAppOps.noteOp(STREAM_VOLUME_OPS[streamTypeAlias2], flags22, callingPackage) != 0) {
            return;
        }
        synchronized (this.mSafeMediaVolumeStateLock) {
            try {
                this.mPendingVolumeCommand = null;
            } finally {
                th = th;
                while (true) {
                    try {
                        break;
                    } catch (Throwable th) {
                        th = th;
                    }
                }
            }
        }
        int flags4 = flags & (-33);
        if (streamTypeAlias2 != 3 || (this.mFixedVolumeDevices & device2) == 0) {
            step = rescaleIndex(10, streamType2, streamTypeAlias2);
            aliasIndex = aliasIndex2;
        } else {
            flags4 |= 32;
            if (this.mSafeMediaVolumeState == 3 && (67108876 & device2) != 0) {
                step2 = safeMediaVolumeIndex(device2);
            } else {
                step2 = streamState3.getMaxIndex();
            }
            if (aliasIndex2 == 0) {
                step = step2;
                aliasIndex = aliasIndex2;
            } else {
                step = step2;
                aliasIndex = step2;
            }
        }
        if ((flags4 & 2) != 0 || streamTypeAlias2 == getUiSoundsStreamType()) {
            int ringerMode = getRingerModeInternal();
            if (ringerMode == 1) {
                flags4 &= -17;
            }
            device = device2;
            streamState = streamState3;
            streamTypeAlias = streamTypeAlias2;
            isMuteAdjust = isMuteAdjust2;
            i = 3;
            int result = checkForRingerModeChange(aliasIndex, direction, step, streamState3.mIsMuted, callingPackage, flags4);
            adjustVolume2 = (result & 1) != 0;
            if ((result & 128) != 0) {
                flags4 |= 128;
            }
            if ((result & 2048) != 0) {
                flags3 = flags22 | 2048;
            }
        } else {
            device = device2;
            streamState = streamState3;
            streamTypeAlias = streamTypeAlias2;
            i = 3;
            flags3 = flags4;
            isMuteAdjust = isMuteAdjust2;
        }
        if (volumeAdjustmentAllowedByDnd(streamTypeAlias, flags3)) {
            adjustVolume = adjustVolume2;
        } else {
            adjustVolume = false;
        }
        int oldIndex = this.mStreamStates[streamType2].getIndex(device);
        if (adjustVolume && direction != 0) {
            this.mAudioHandler.removeMessages(18);
            if (isMuteAdjust) {
                if (direction != 101) {
                    state = direction == -100;
                } else {
                    state = !streamState.mIsMuted;
                }
                if (streamTypeAlias == i) {
                    setSystemAudioMute(state);
                }
                for (int stream = 0; stream < this.mStreamStates.length; stream++) {
                    if (streamTypeAlias == mStreamVolumeAlias[stream] && (!readCameraSoundForced() || this.mStreamStates[stream].getStreamType() != 7)) {
                        this.mStreamStates[stream].mute(state);
                    }
                }
                streamState2 = streamState;
                i2 = i;
            } else if (direction == 1 && !checkSafeMediaVolume(streamTypeAlias, aliasIndex + step, device)) {
                Log.e(TAG, "adjustStreamVolume() safe volume index = " + oldIndex);
                this.mVolumeController.postDisplaySafeVolumeWarning(flags3);
                streamState2 = streamState;
                i2 = i;
            } else if ((this.mFullVolumeDevices & device) != 0) {
                streamState2 = streamState;
                i2 = i;
            } else {
                streamState2 = streamState;
                i2 = i;
                if (streamState2.adjustIndex(direction * step, device, caller) || streamState2.mIsMuted) {
                    if (streamState2.mIsMuted) {
                        if (direction == 1) {
                            streamState2.mute(false);
                        } else if (direction == -1 && this.mIsSingleVolume) {
                            sendMsg(this.mAudioHandler, 18, 2, streamTypeAlias, flags3, null, UNMUTE_STREAM_DELAY);
                        }
                    }
                    sendMsg(this.mAudioHandler, 0, 2, device, 0, streamState2, 0);
                }
            }
            int newIndex = this.mStreamStates[streamType2].getIndex(device);
            if (streamTypeAlias == i2 && (device & 896) != 0 && (flags3 & 64) == 0) {
                Log.d(TAG, "adjustSreamVolume: postSetAvrcpAbsoluteVolumeIndex index=" + newIndex + "stream=" + streamType2);
                this.mDeviceBroker.postSetAvrcpAbsoluteVolumeIndex(newIndex / 10);
            }
            if ((134217728 & device) != 0 && streamType2 == getHearingAidStreamType()) {
                Log.d(TAG, "adjustSreamVolume postSetHearingAidVolumeIndex index=" + newIndex + " stream=" + streamType2);
                this.mDeviceBroker.postSetHearingAidVolumeIndex(newIndex, streamType2);
            }
            if (streamTypeAlias == i2) {
                setSystemAudioVolume(oldIndex, newIndex, getStreamMaxVolume(streamType), flags3);
            }
            synchronized (this.mHdmiClientLock) {
                try {
                    try {
                        if (this.mHdmiManager != null) {
                            if (this.mHdmiCecSink && streamTypeAlias == i2) {
                                try {
                                    if ((this.mFullVolumeDevices & device) != 0) {
                                        int keyCode = 0;
                                        if (direction == -1) {
                                            keyCode = 25;
                                        } else if (direction == 1) {
                                            keyCode = 24;
                                        } else if (direction == 101) {
                                            keyCode = 164;
                                        }
                                        if (keyCode != 0) {
                                            long ident = Binder.clearCallingIdentity();
                                            this.mHdmiPlaybackClient.sendKeyEvent(keyCode, true);
                                            this.mHdmiPlaybackClient.sendKeyEvent(keyCode, false);
                                            Binder.restoreCallingIdentity(ident);
                                        }
                                    }
                                } catch (Throwable th2) {
                                    th = th2;
                                    throw th;
                                }
                            }
                            if (this.mHdmiAudioSystemClient != null && this.mHdmiSystemAudioSupported && streamTypeAlias == 3) {
                                if (oldIndex == newIndex && !isMuteAdjust) {
                                    streamState = streamState2;
                                }
                                long identity = Binder.clearCallingIdentity();
                                streamState = streamState2;
                                this.mHdmiAudioSystemClient.sendReportAudioStatusCecCommand(isMuteAdjust, getStreamVolume(3), getStreamMaxVolume(3), isStreamMute(3));
                                Binder.restoreCallingIdentity(identity);
                            } else {
                                streamState = streamState2;
                            }
                        } else {
                            streamState = streamState2;
                        }
                    } catch (Throwable th3) {
                        th = th3;
                    }
                } catch (Throwable th4) {
                    th = th4;
                }
            }
        }
        int index = this.mStreamStates[streamType2].getIndex(device);
        sendVolumeUpdate(streamType, oldIndex, index, flags3, device);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onUnmuteStream(int stream, int flags) {
        VolumeStreamState streamState = this.mStreamStates[stream];
        streamState.mute(false);
        int device = getDeviceForStream(stream);
        int index = this.mStreamStates[stream].getIndex(device);
        sendVolumeUpdate(stream, index, index, flags, device);
    }

    private void setSystemAudioVolume(int oldVolume, int newVolume, int maxVolume, int flags) {
        synchronized (this.mHdmiClientLock) {
            if (this.mHdmiManager != null && this.mHdmiTvClient != null && oldVolume != newVolume && (flags & 256) == 0 && this.mHdmiSystemAudioSupported) {
                long token = Binder.clearCallingIdentity();
                this.mHdmiTvClient.setSystemAudioVolume(oldVolume, newVolume, maxVolume);
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class StreamVolumeCommand {
        public final int mDevice;
        public final int mFlags;
        public final int mIndex;
        public final int mStreamType;

        StreamVolumeCommand(int streamType, int index, int flags, int device) {
            this.mStreamType = streamType;
            this.mIndex = index;
            this.mFlags = flags;
            this.mDevice = device;
        }

        public String toString() {
            return "{streamType=" + this.mStreamType + ",index=" + this.mIndex + ",flags=" + this.mFlags + ",device=" + this.mDevice + '}';
        }
    }

    private int getNewRingerMode(int stream, int index, int flags) {
        if (this.mIsSingleVolume) {
            return getRingerModeExternal();
        }
        if ((flags & 2) != 0 || stream == getUiSoundsStreamType()) {
            if (index == 0) {
                if (this.mHasVibrator) {
                    return 1;
                }
                return this.mVolumePolicy.volumeDownToEnterSilent ? 0 : 2;
            }
            return 2;
        }
        return getRingerModeExternal();
    }

    private boolean isAndroidNPlus(String caller) {
        try {
            ApplicationInfo applicationInfo = this.mContext.getPackageManager().getApplicationInfoAsUser(caller, 0, UserHandle.getUserId(Binder.getCallingUid()));
            if (applicationInfo.targetSdkVersion >= 24) {
                return true;
            }
            return false;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private boolean wouldToggleZenMode(int newMode) {
        if (getRingerModeExternal() != 0 || newMode == 0) {
            return getRingerModeExternal() != 0 && newMode == 0;
        }
        return true;
    }

    private void onSetStreamVolume(int streamType, int index, int flags, int device, String caller) {
        int stream = mStreamVolumeAlias[streamType];
        setStreamVolumeInt(stream, index, device, false, caller);
        if ((flags & 2) != 0 || stream == getUiSoundsStreamType()) {
            setRingerMode(getNewRingerMode(stream, index, flags), "AS.AudioService.onSetStreamVolume", false);
        }
        if (streamType != 6) {
            this.mStreamStates[stream].mute(index == 0);
        }
    }

    private void enforceModifyAudioRoutingPermission() {
        if (this.mContext.checkCallingPermission("android.permission.MODIFY_AUDIO_ROUTING") != 0) {
            throw new SecurityException("Missing MODIFY_AUDIO_ROUTING permission");
        }
    }

    public void setVolumeIndexForAttributes(AudioAttributes attr, int index, int flags, String callingPackage) {
        enforceModifyAudioRoutingPermission();
        Preconditions.checkNotNull(attr, "attr must not be null");
        int stream = AudioProductStrategy.getLegacyStreamTypeForStrategyWithAudioAttributes(attr);
        int device = getDeviceForStream(stream);
        AudioSystem.getVolumeIndexForAttributes(attr, device);
        AudioSystem.setVolumeIndexForAttributes(attr, index, device);
        int volumeGroup = getVolumeGroupIdForAttributes(attr);
        AudioVolumeGroup avg = getAudioVolumeGroupById(volumeGroup);
        if (avg == null) {
            return;
        }
        int[] legacyStreamTypes = avg.getLegacyStreamTypes();
        int i = 0;
        for (int length = legacyStreamTypes.length; i < length; length = length) {
            int groupedStream = legacyStreamTypes[i];
            setStreamVolume(groupedStream, index, flags, callingPackage, callingPackage, Binder.getCallingUid());
            i++;
        }
    }

    private AudioVolumeGroup getAudioVolumeGroupById(int volumeGroupId) {
        for (AudioVolumeGroup avg : AudioVolumeGroup.getAudioVolumeGroups()) {
            if (avg.getId() == volumeGroupId) {
                return avg;
            }
        }
        Log.e(TAG, ": invalid volume group id: " + volumeGroupId + " requested");
        return null;
    }

    public int getVolumeIndexForAttributes(AudioAttributes attr) {
        enforceModifyAudioRoutingPermission();
        Preconditions.checkNotNull(attr, "attr must not be null");
        int stream = AudioProductStrategy.getLegacyStreamTypeForStrategyWithAudioAttributes(attr);
        int device = getDeviceForStream(stream);
        return AudioSystem.getVolumeIndexForAttributes(attr, device);
    }

    public int getMaxVolumeIndexForAttributes(AudioAttributes attr) {
        enforceModifyAudioRoutingPermission();
        Preconditions.checkNotNull(attr, "attr must not be null");
        return AudioSystem.getMaxVolumeIndexForAttributes(attr);
    }

    public int getMinVolumeIndexForAttributes(AudioAttributes attr) {
        enforceModifyAudioRoutingPermission();
        Preconditions.checkNotNull(attr, "attr must not be null");
        return AudioSystem.getMinVolumeIndexForAttributes(attr);
    }

    public void setStreamVolume(int streamType, int index, int flags, String callingPackage) {
        Log.d(TAG, "setStreamVolume streamType:" + streamType + " index:" + index + " callingPackage:" + callingPackage);
        if (streamType == 10 && !canChangeAccessibilityVolume()) {
            Log.w(TAG, "Trying to call setStreamVolume() for a11y without CHANGE_ACCESSIBILITY_VOLUME  callingPackage=" + callingPackage);
            return;
        }
        if (streamType == 0 && index == 0) {
            if (this.mContext.checkCallingOrSelfPermission("android.permission.MODIFY_PHONE_STATE") != 0) {
                Log.w(TAG, "Trying to call setStreamVolume() for STREAM_VOICE_CALL and index 0 without MODIFY_PHONE_STATE  callingPackage=" + callingPackage);
                return;
            }
        }
        sVolumeLogger.log(new AudioServiceEvents.VolumeEvent(2, streamType, index, flags, callingPackage));
        setStreamVolume(streamType, index, flags, callingPackage, callingPackage, Binder.getCallingUid());
    }

    private boolean canChangeAccessibilityVolume() {
        synchronized (this.mAccessibilityServiceUidsLock) {
            if (this.mContext.checkCallingOrSelfPermission("android.permission.CHANGE_ACCESSIBILITY_VOLUME") == 0) {
                return true;
            }
            if (this.mAccessibilityServiceUids != null) {
                int callingUid = Binder.getCallingUid();
                for (int i = 0; i < this.mAccessibilityServiceUids.length; i++) {
                    if (this.mAccessibilityServiceUids[i] == callingUid) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getHearingAidStreamType() {
        return getHearingAidStreamType(this.mMode);
    }

    private int getHearingAidStreamType(int mode) {
        return (mode == 2 || mode == 3 || this.mVoiceActive.get()) ? 0 : 3;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onPlaybackConfigChange(List<AudioPlaybackConfiguration> configs) {
        boolean voiceActive = false;
        Iterator<AudioPlaybackConfiguration> it = configs.iterator();
        while (true) {
            if (!it.hasNext()) {
                break;
            }
            AudioPlaybackConfiguration config = it.next();
            int usage = config.getAudioAttributes().getUsage();
            if (usage == 2 || usage == 3) {
                if (config.getPlayerState() == 2) {
                    voiceActive = true;
                    break;
                }
            }
        }
        if (this.mVoiceActive.getAndSet(voiceActive) != voiceActive) {
            updateHearingAidVolumeOnVoiceActivityUpdate();
        }
    }

    private void updateHearingAidVolumeOnVoiceActivityUpdate() {
        int streamType = getHearingAidStreamType();
        int index = getStreamVolume(streamType);
        sVolumeLogger.log(new AudioServiceEvents.VolumeEvent(6, this.mVoiceActive.get(), streamType, index));
        this.mDeviceBroker.postSetHearingAidVolumeIndex(index * 10, streamType);
    }

    void updateAbsVolumeMultiModeDevices(int oldMode, int newMode) {
        if (oldMode == newMode) {
            return;
        }
        if (newMode != 0) {
            if (newMode == 1) {
                return;
            }
            if (newMode != 2 && newMode != 3) {
                return;
            }
        }
        int streamType = getHearingAidStreamType(newMode);
        int device = AudioSystem.getDevicesForStream(streamType);
        int i = this.mAbsVolumeMultiModeCaseDevices;
        if ((device & i) != 0 && (i & device) == 134217728) {
            int index = getStreamVolume(streamType);
            sVolumeLogger.log(new AudioServiceEvents.VolumeEvent(7, newMode, streamType, index));
            this.mDeviceBroker.postSetHearingAidVolumeIndex(index * 10, streamType);
        }
    }

    public void setVolumeToHal(int streamType) {
        Log.d(TAG, "setVolumeToHal(stream=" + streamType + ")");
        if (this.mUseFixedVolume) {
            if (newPolicyOpen) {
                xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
                if (xuiaudiopolicytrigger != null) {
                    xuiaudiopolicytrigger.setVolWhenPlay(streamType);
                    return;
                }
                return;
            }
            xpAudio xpaudio = this.mXpAudio;
            if (xpaudio != null) {
                xpaudio.setStreamVolume(streamType);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:125:0x01f4 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:78:0x01ba A[Catch: all -> 0x0249, TRY_LEAVE, TryCatch #6 {all -> 0x0249, blocks: (B:76:0x01b4, B:78:0x01ba), top: B:131:0x01b4 }] */
    /* JADX WARN: Removed duplicated region for block: B:81:0x01d5 A[Catch: all -> 0x0243, TRY_LEAVE, TryCatch #1 {all -> 0x0243, blocks: (B:80:0x01cd, B:81:0x01d5), top: B:121:0x01b8 }] */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:117:0x0256 -> B:115:0x0254). Please submit an issue!!! */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void setStreamVolume(int r19, int r20, int r21, java.lang.String r22, java.lang.String r23, int r24) {
        /*
            Method dump skipped, instructions count: 600
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.audio.AudioService.setStreamVolume(int, int, int, java.lang.String, java.lang.String, int):void");
    }

    private int getVolumeGroupIdForAttributes(AudioAttributes attributes) {
        Preconditions.checkNotNull(attributes, "attributes must not be null");
        int volumeGroupId = getVolumeGroupIdForAttributesInt(attributes);
        if (volumeGroupId != -1) {
            return volumeGroupId;
        }
        return getVolumeGroupIdForAttributesInt(AudioProductStrategy.sDefaultAttributes);
    }

    private int getVolumeGroupIdForAttributesInt(AudioAttributes attributes) {
        Preconditions.checkNotNull(attributes, "attributes must not be null");
        for (AudioProductStrategy productStrategy : AudioProductStrategy.getAudioProductStrategies()) {
            int volumeGroupId = productStrategy.getVolumeGroupIdForAudioAttributes(attributes);
            if (volumeGroupId != -1) {
                return volumeGroupId;
            }
        }
        return -1;
    }

    private boolean volumeAdjustmentAllowedByDnd(int streamTypeAlias, int flags) {
        int zenMode = this.mNm.getZenMode();
        if (zenMode != 0) {
            return ((zenMode == 1 || zenMode == 2 || zenMode == 3) && isStreamMutedByRingerOrZenMode(streamTypeAlias) && streamTypeAlias != getUiSoundsStreamType() && (flags & 2) == 0) ? false : true;
        }
        return true;
    }

    public void forceVolumeControlStream(int streamType, IBinder cb) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.MODIFY_PHONE_STATE") != 0) {
            return;
        }
        Log.d(TAG, String.format("forceVolumeControlStream(%d)", Integer.valueOf(streamType)));
        synchronized (this.mForceControlStreamLock) {
            if (this.mVolumeControlStream != -1 && streamType != -1) {
                this.mUserSelectedVolumeControlStream = true;
            }
            this.mVolumeControlStream = streamType;
            if (this.mVolumeControlStream == -1) {
                if (this.mForceControlStreamClient != null) {
                    this.mForceControlStreamClient.release();
                    this.mForceControlStreamClient = null;
                }
                this.mUserSelectedVolumeControlStream = false;
            } else if (this.mForceControlStreamClient == null) {
                this.mForceControlStreamClient = new ForceControlStreamClient(cb);
            } else if (this.mForceControlStreamClient.getBinder() == cb) {
                Log.d(TAG, "forceVolumeControlStream cb:" + cb + " is already linked.");
            } else {
                this.mForceControlStreamClient.release();
                this.mForceControlStreamClient = new ForceControlStreamClient(cb);
            }
        }
    }

    /* loaded from: classes.dex */
    private class ForceControlStreamClient implements IBinder.DeathRecipient {
        private IBinder mCb;

        ForceControlStreamClient(IBinder cb) {
            if (cb != null) {
                try {
                    cb.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    Log.w(AudioService.TAG, "ForceControlStreamClient() could not link to " + cb + " binder death");
                    cb = null;
                }
            }
            this.mCb = cb;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            synchronized (AudioService.this.mForceControlStreamLock) {
                Log.w(AudioService.TAG, "SCO client died");
                if (AudioService.this.mForceControlStreamClient == this) {
                    AudioService.this.mForceControlStreamClient = null;
                    AudioService.this.mVolumeControlStream = -1;
                    AudioService.this.mUserSelectedVolumeControlStream = false;
                } else {
                    Log.w(AudioService.TAG, "unregistered control stream client died");
                }
            }
        }

        public void release() {
            IBinder iBinder = this.mCb;
            if (iBinder != null) {
                iBinder.unlinkToDeath(this, 0);
                this.mCb = null;
            }
        }

        public IBinder getBinder() {
            return this.mCb;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendBroadcastToAll(Intent intent) {
        intent.addFlags(67108864);
        intent.addFlags(268435456);
        long ident = Binder.clearCallingIdentity();
        try {
            this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void sendStickyBroadcastToAll(Intent intent) {
        intent.addFlags(268435456);
        long ident = Binder.clearCallingIdentity();
        try {
            this.mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int getCurrentUserId() {
        long ident = Binder.clearCallingIdentity();
        try {
            UserInfo currentUser = ActivityManager.getService().getCurrentUser();
            int i = currentUser.id;
            Binder.restoreCallingIdentity(ident);
            return i;
        } catch (RemoteException e) {
            Binder.restoreCallingIdentity(ident);
            return 0;
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
    }

    protected void sendVolumeUpdate(int streamType, int oldIndex, int index, int flags, int device) {
        int streamType2 = mStreamVolumeAlias[streamType];
        if (streamType2 == 3) {
            flags = updateFlagsForTvPlatform(flags);
            if ((this.mFullVolumeDevices & device) != 0) {
                flags &= -2;
            }
        }
        this.mVolumeController.postVolumeChanged(streamType2, flags);
    }

    private int updateFlagsForTvPlatform(int flags) {
        synchronized (this.mHdmiClientLock) {
            if (this.mHdmiTvClient != null && this.mHdmiSystemAudioSupported && (flags & 256) == 0) {
                flags &= -2;
            }
        }
        return flags;
    }

    private void sendMasterMuteUpdate(boolean muted, int flags) {
        this.mVolumeController.postMasterMuteChanged(updateFlagsForTvPlatform(flags));
        broadcastMasterMuteStatus(muted);
    }

    private void broadcastMasterMuteStatus(boolean muted) {
        Intent intent = new Intent("android.media.MASTER_MUTE_CHANGED_ACTION");
        intent.putExtra("android.media.EXTRA_MASTER_VOLUME_MUTED", muted);
        intent.addFlags(603979776);
        sendStickyBroadcastToAll(intent);
    }

    private void setStreamVolumeInt(int streamType, int index, int device, boolean force, String caller) {
        if ((this.mFullVolumeDevices & device) != 0) {
            return;
        }
        VolumeStreamState streamState = this.mStreamStates[streamType];
        if (streamState.setIndex(index, device, caller) || force) {
            sendMsg(this.mAudioHandler, 0, 2, device, 0, streamState, 0);
        }
    }

    private void setSystemAudioMute(boolean state) {
        synchronized (this.mHdmiClientLock) {
            if (this.mHdmiManager != null && this.mHdmiTvClient != null && this.mHdmiSystemAudioSupported) {
                long token = Binder.clearCallingIdentity();
                this.mHdmiTvClient.setSystemAudioMute(state);
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    public boolean isStreamMute(int streamType) {
        boolean z;
        if (this.mUseFixedVolume) {
            if (newPolicyOpen) {
                xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
                if (xuiaudiopolicytrigger != null) {
                    return xuiaudiopolicytrigger.getStreamMute(streamType);
                }
            } else {
                xpAudio xpaudio = this.mXpAudio;
                if (xpaudio != null) {
                    return xpaudio.getStreamMute(streamType);
                }
            }
        }
        if (streamType == Integer.MIN_VALUE) {
            streamType = getActiveStreamType(streamType);
        }
        synchronized (VolumeStreamState.class) {
            ensureValidStreamType(streamType);
            z = this.mStreamStates[streamType].mIsMuted;
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class RmtSbmxFullVolDeathHandler implements IBinder.DeathRecipient {
        private IBinder mICallback;

        RmtSbmxFullVolDeathHandler(IBinder cb) {
            this.mICallback = cb;
            try {
                cb.linkToDeath(this, 0);
            } catch (RemoteException e) {
                Log.e(AudioService.TAG, "can't link to death", e);
            }
        }

        boolean isHandlerFor(IBinder cb) {
            return this.mICallback.equals(cb);
        }

        void forget() {
            try {
                this.mICallback.unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                Log.e(AudioService.TAG, "error unlinking to death", e);
            }
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            Log.w(AudioService.TAG, "Recorder with remote submix at full volume died " + this.mICallback);
            AudioService.this.forceRemoteSubmixFullVolume(false, this.mICallback);
        }
    }

    private boolean discardRmtSbmxFullVolDeathHandlerFor(IBinder cb) {
        Iterator<RmtSbmxFullVolDeathHandler> it = this.mRmtSbmxFullVolDeathHandlers.iterator();
        while (it.hasNext()) {
            RmtSbmxFullVolDeathHandler handler = it.next();
            if (handler.isHandlerFor(cb)) {
                handler.forget();
                this.mRmtSbmxFullVolDeathHandlers.remove(handler);
                return true;
            }
        }
        return false;
    }

    private boolean hasRmtSbmxFullVolDeathHandlerFor(IBinder cb) {
        Iterator<RmtSbmxFullVolDeathHandler> it = this.mRmtSbmxFullVolDeathHandlers.iterator();
        while (it.hasNext()) {
            if (it.next().isHandlerFor(cb)) {
                return true;
            }
        }
        return false;
    }

    public void forceRemoteSubmixFullVolume(boolean startForcing, IBinder cb) {
        if (cb == null) {
            return;
        }
        if (this.mContext.checkCallingOrSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT") != 0) {
            Log.w(TAG, "Trying to call forceRemoteSubmixFullVolume() without CAPTURE_AUDIO_OUTPUT");
            return;
        }
        synchronized (this.mRmtSbmxFullVolDeathHandlers) {
            boolean applyRequired = false;
            if (startForcing) {
                if (!hasRmtSbmxFullVolDeathHandlerFor(cb)) {
                    this.mRmtSbmxFullVolDeathHandlers.add(new RmtSbmxFullVolDeathHandler(cb));
                    if (this.mRmtSbmxFullVolRefCount == 0) {
                        this.mFullVolumeDevices |= 32768;
                        this.mFixedVolumeDevices |= 32768;
                        applyRequired = true;
                    }
                    this.mRmtSbmxFullVolRefCount++;
                }
            } else if (discardRmtSbmxFullVolDeathHandlerFor(cb) && this.mRmtSbmxFullVolRefCount > 0) {
                this.mRmtSbmxFullVolRefCount--;
                if (this.mRmtSbmxFullVolRefCount == 0) {
                    this.mFullVolumeDevices &= -32769;
                    this.mFixedVolumeDevices &= -32769;
                    applyRequired = true;
                }
            }
            if (applyRequired) {
                checkAllFixedVolumeDevices(3);
                this.mStreamStates[3].applyAllVolumes();
            }
        }
    }

    private void setMasterMuteInternal(boolean mute, int flags, String callingPackage, int uid, int userId) {
        if (uid == 1000) {
            uid = UserHandle.getUid(userId, UserHandle.getAppId(uid));
        }
        if (!mute && this.mAppOps.noteOp(33, uid, callingPackage) != 0) {
            return;
        }
        if (userId != UserHandle.getCallingUserId() && this.mContext.checkCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL") != 0) {
            return;
        }
        setMasterMuteInternalNoCallerCheck(mute, flags, userId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setMasterMuteInternalNoCallerCheck(boolean mute, int flags, int userId) {
        Log.d(TAG, String.format("Master mute %s, %d, user=%d", Boolean.valueOf(mute), Integer.valueOf(flags), Integer.valueOf(userId)));
        if (!isPlatformAutomotive() && this.mUseFixedVolume) {
            return;
        }
        if (((isPlatformAutomotive() && userId == 0) || getCurrentUserId() == userId) && mute != AudioSystem.getMasterMute()) {
            setSystemAudioMute(mute);
            AudioSystem.setMasterMute(mute);
            sendMasterMuteUpdate(mute, flags);
        }
    }

    public boolean isMasterMute() {
        return AudioSystem.getMasterMute();
    }

    public void setMasterMute(boolean mute, int flags, String callingPackage, int userId) {
        enforceModifyAudioRoutingPermission();
        setMasterMuteInternal(mute, flags, callingPackage, Binder.getCallingUid(), userId);
    }

    public int getStreamVolume(int streamType) {
        int i;
        ensureValidStreamType(streamType);
        if (this.mUseFixedVolume) {
            if (newPolicyOpen) {
                xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
                if (xuiaudiopolicytrigger != null) {
                    return xuiaudiopolicytrigger.getStreamVolume(streamType);
                }
            } else {
                xpAudio xpaudio = this.mXpAudio;
                if (xpaudio != null) {
                    return xpaudio.getStreamVolume(streamType);
                }
            }
        }
        int device = getDeviceForStream(streamType);
        synchronized (VolumeStreamState.class) {
            int index = this.mStreamStates[streamType].getIndex(device);
            if (this.mStreamStates[streamType].mIsMuted) {
                index = 0;
            }
            if (index != 0 && mStreamVolumeAlias[streamType] == 3 && (this.mFixedVolumeDevices & device) != 0) {
                index = this.mStreamStates[streamType].getMaxIndex();
            }
            i = (index + 5) / 10;
        }
        return i;
    }

    public int getStreamMaxVolume(int streamType) {
        ensureValidStreamType(streamType);
        if (this.mUseFixedVolume) {
            if (newPolicyOpen) {
                xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
                if (xuiaudiopolicytrigger != null) {
                    return xuiaudiopolicytrigger.getStreamMaxVolume(streamType);
                }
            } else {
                xpAudio xpaudio = this.mXpAudio;
                if (xpaudio != null) {
                    return xpaudio.getStreamMaxVolume(streamType);
                }
            }
        }
        return (this.mStreamStates[streamType].getMaxIndex() + 5) / 10;
    }

    public int getStreamMinVolume(int streamType) {
        ensureValidStreamType(streamType);
        if (this.mUseFixedVolume) {
            if (newPolicyOpen) {
                xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
                if (xuiaudiopolicytrigger != null) {
                    return xuiaudiopolicytrigger.getStreamMinVolume(streamType);
                }
            } else {
                xpAudio xpaudio = this.mXpAudio;
                if (xpaudio != null) {
                    return xpaudio.getStreamMinVolume(streamType);
                }
            }
        }
        return (this.mStreamStates[streamType].getMinIndex() + 5) / 10;
    }

    public int getLastAudibleStreamVolume(int streamType) {
        ensureValidStreamType(streamType);
        if (this.mUseFixedVolume) {
            if (newPolicyOpen) {
                xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
                if (xuiaudiopolicytrigger != null) {
                    return xuiaudiopolicytrigger.getLastAudibleStreamVolume(streamType);
                }
            } else {
                xpAudio xpaudio = this.mXpAudio;
                if (xpaudio != null) {
                    return xpaudio.getLastAudibleStreamVolume(streamType);
                }
            }
        }
        int device = getDeviceForStream(streamType);
        return (this.mStreamStates[streamType].getIndex(device) + 5) / 10;
    }

    public int getUiSoundsStreamType() {
        return mStreamVolumeAlias[1];
    }

    public void setMicrophoneMute(boolean on, String callingPackage, int userId) {
        int uid = Binder.getCallingUid();
        if (uid == 1000) {
            uid = UserHandle.getUid(userId, UserHandle.getAppId(uid));
        }
        if ((!on && this.mAppOps.noteOp(44, uid, callingPackage) != 0) || !checkAudioSettingsPermission("setMicrophoneMute()")) {
            return;
        }
        if (userId != UserHandle.getCallingUserId() && this.mContext.checkCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL") != 0) {
            return;
        }
        setMicrophoneMuteNoCallerCheck(on, userId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setMicrophoneMuteNoCallerCheck(boolean on, int userId) {
        Log.d(TAG, String.format("Mic mute %s, user=%d", Boolean.valueOf(on), Integer.valueOf(userId)));
        if (getCurrentUserId() == userId) {
            boolean currentMute = AudioSystem.isMicrophoneMuted();
            long identity = Binder.clearCallingIdentity();
            SystemProperties.set(SYSTEM_MIC_MUTE, on ? "true" : "false");
            AudioSystem.muteMicrophone(on);
            Binder.restoreCallingIdentity(identity);
            if (on != currentMute) {
                this.mContext.sendBroadcast(new Intent("android.media.action.MICROPHONE_MUTE_CHANGED").setFlags(1073741824));
            }
        }
    }

    public int getRingerModeExternal() {
        int i;
        synchronized (this.mSettingsLock) {
            i = this.mRingerModeExternal;
        }
        return i;
    }

    public int getRingerModeInternal() {
        int i;
        synchronized (this.mSettingsLock) {
            i = this.mRingerMode;
        }
        return i;
    }

    private void ensureValidRingerMode(int ringerMode) {
        if (!isValidRingerMode(ringerMode)) {
            throw new IllegalArgumentException("Bad ringer mode " + ringerMode);
        }
    }

    public boolean isValidRingerMode(int ringerMode) {
        return ringerMode >= 0 && ringerMode <= 2;
    }

    public void setRingerModeExternal(int ringerMode, String caller) {
        if (isAndroidNPlus(caller) && wouldToggleZenMode(ringerMode) && !this.mNm.isNotificationPolicyAccessGrantedForPackage(caller)) {
            throw new SecurityException("Not allowed to change Do Not Disturb state");
        }
        setRingerMode(ringerMode, caller, true);
    }

    public void setRingerModeInternal(int ringerMode, String caller) {
        enforceVolumeController("setRingerModeInternal");
        setRingerMode(ringerMode, caller, false);
    }

    public void silenceRingerModeInternal(String reason) {
        VibrationEffect effect = null;
        int ringerMode = 0;
        int toastText = 0;
        int silenceRingerSetting = 0;
        if (this.mContext.getResources().getBoolean(17891576)) {
            silenceRingerSetting = Settings.Secure.getIntForUser(this.mContentResolver, "volume_hush_gesture", 0, -2);
        }
        if (silenceRingerSetting == 1) {
            effect = VibrationEffect.get(5);
            ringerMode = 1;
            toastText = 17041213;
        } else if (silenceRingerSetting == 2) {
            effect = VibrationEffect.get(1);
            ringerMode = 0;
            toastText = 17041212;
        }
        maybeVibrate(effect, reason);
        setRingerModeInternal(ringerMode, reason);
        Toast.makeText(this.mContext, toastText, 0).show();
    }

    private boolean maybeVibrate(VibrationEffect effect, String reason) {
        if (this.mHasVibrator) {
            boolean hapticsDisabled = Settings.System.getIntForUser(this.mContext.getContentResolver(), "haptic_feedback_enabled", 0, -2) == 0;
            if (hapticsDisabled || effect == null) {
                return false;
            }
            this.mVibrator.vibrate(Binder.getCallingUid(), this.mContext.getOpPackageName(), effect, reason, VIBRATION_ATTRIBUTES);
            return true;
        }
        return false;
    }

    private void setRingerMode(int ringerMode, String caller, boolean external) {
        int ringerMode2;
        if (!this.mUseFixedVolume && !this.mIsSingleVolume) {
            if (caller == null || caller.length() == 0) {
                throw new IllegalArgumentException("Bad caller: " + caller);
            }
            ensureValidRingerMode(ringerMode);
            if (ringerMode == 1 && !this.mHasVibrator) {
                ringerMode2 = 0;
            } else {
                ringerMode2 = ringerMode;
            }
            long identity = Binder.clearCallingIdentity();
            try {
                synchronized (this.mSettingsLock) {
                    int ringerModeInternal = getRingerModeInternal();
                    int ringerModeExternal = getRingerModeExternal();
                    if (external) {
                        setRingerModeExt(ringerMode2);
                        if (this.mRingerModeDelegate != null) {
                            ringerMode2 = this.mRingerModeDelegate.onSetRingerModeExternal(ringerModeExternal, ringerMode2, caller, ringerModeInternal, this.mVolumePolicy);
                        }
                        if (ringerMode2 != ringerModeInternal) {
                            setRingerModeInt(ringerMode2, true);
                        }
                    } else {
                        if (ringerMode2 != ringerModeInternal) {
                            setRingerModeInt(ringerMode2, true);
                        }
                        if (this.mRingerModeDelegate != null) {
                            ringerMode2 = this.mRingerModeDelegate.onSetRingerModeInternal(ringerModeInternal, ringerMode2, caller, ringerModeExternal, this.mVolumePolicy);
                        }
                        setRingerModeExt(ringerMode2);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private void setRingerModeExt(int ringerMode) {
        synchronized (this.mSettingsLock) {
            if (ringerMode == this.mRingerModeExternal) {
                return;
            }
            this.mRingerModeExternal = ringerMode;
            broadcastRingerMode("android.media.RINGER_MODE_CHANGED", ringerMode);
        }
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:69:? -> B:61:0x00f0). Please submit an issue!!! */
    @GuardedBy({"mSettingsLock"})
    private void muteRingerModeStreams() {
        int numStreamTypes;
        int numStreamTypes2;
        int numStreamTypes3;
        int numStreamTypes4 = AudioSystem.getNumStreamTypes();
        if (this.mNm == null) {
            this.mNm = (NotificationManager) this.mContext.getSystemService("notification");
        }
        int ringerMode = this.mRingerMode;
        int i = 0;
        boolean z = true;
        boolean ringerModeMute = ringerMode == 1 || ringerMode == 0;
        boolean shouldRingSco = ringerMode == 1 && isBluetoothScoOn();
        String eventSource = "muteRingerModeStreams() from u/pid:" + Binder.getCallingUid() + SliceClientPermissions.SliceAuthority.DELIMITER + Binder.getCallingPid();
        sendMsg(this.mAudioHandler, 8, 2, 7, shouldRingSco ? 3 : 0, eventSource, 0);
        int streamType = numStreamTypes4 - 1;
        while (streamType >= 0) {
            boolean isMuted = isStreamMutedByRingerOrZenMode(streamType);
            int i2 = (shouldRingSco && streamType == 2) ? i : z ? 1 : 0;
            boolean shouldZenMute = shouldZenMuteStream(streamType);
            int i3 = (shouldZenMute || (ringerModeMute && isStreamAffectedByRingerMode(streamType) && i2 != 0)) ? z ? 1 : 0 : i;
            if (isMuted == i3) {
                numStreamTypes = numStreamTypes4;
                numStreamTypes2 = i;
            } else if (i3 == 0) {
                if (mStreamVolumeAlias[streamType] != 2) {
                    numStreamTypes = numStreamTypes4;
                } else {
                    synchronized (VolumeStreamState.class) {
                        try {
                            VolumeStreamState vss = this.mStreamStates[streamType];
                            int i4 = i;
                            while (i4 < vss.mIndexMap.size()) {
                                int device = vss.mIndexMap.keyAt(i4);
                                int value = vss.mIndexMap.valueAt(i4);
                                if (value == 0) {
                                    numStreamTypes3 = numStreamTypes4;
                                    try {
                                        vss.setIndex(10, device, TAG);
                                    } catch (Throwable th) {
                                        th = th;
                                        throw th;
                                    }
                                } else {
                                    numStreamTypes3 = numStreamTypes4;
                                }
                                i4++;
                                numStreamTypes4 = numStreamTypes3;
                            }
                            numStreamTypes = numStreamTypes4;
                            int device2 = getDeviceForStream(streamType);
                            sendMsg(this.mAudioHandler, 1, 2, device2, 0, this.mStreamStates[streamType], 500);
                        } catch (Throwable th2) {
                            th = th2;
                            throw th;
                        }
                    }
                }
                numStreamTypes2 = 0;
                this.mStreamStates[streamType].mute(false);
                z = true;
                this.mRingerAndZenModeMutedStreams &= ~(1 << streamType);
            } else {
                numStreamTypes = numStreamTypes4;
                numStreamTypes2 = i;
                this.mStreamStates[streamType].mute(z);
                this.mRingerAndZenModeMutedStreams |= (z ? 1 : 0) << streamType;
            }
            streamType--;
            i = numStreamTypes2;
            numStreamTypes4 = numStreamTypes;
        }
    }

    private boolean isAlarm(int streamType) {
        return streamType == 4;
    }

    private boolean isNotificationOrRinger(int streamType) {
        return streamType == 5 || streamType == 2;
    }

    private boolean isMedia(int streamType) {
        return streamType == 3;
    }

    private boolean isSystem(int streamType) {
        return streamType == 1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setRingerModeInt(int ringerMode, boolean persist) {
        boolean change;
        synchronized (this.mSettingsLock) {
            change = this.mRingerMode != ringerMode;
            this.mRingerMode = ringerMode;
            muteRingerModeStreams();
        }
        if (persist) {
            sendMsg(this.mAudioHandler, 3, 0, 0, 0, null, 500);
        }
        if (change) {
            broadcastRingerMode("android.media.INTERNAL_RINGER_MODE_CHANGED_ACTION", ringerMode);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postUpdateRingerModeServiceInt() {
        sendMsg(this.mAudioHandler, 25, 2, 0, 0, null, 0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onUpdateRingerModeServiceInt() {
        setRingerModeInt(getRingerModeInternal(), false);
    }

    public boolean shouldVibrate(int vibrateType) {
        int vibrateSetting;
        if (this.mHasVibrator && (vibrateSetting = getVibrateSetting(vibrateType)) != 0) {
            return vibrateSetting != 1 ? vibrateSetting == 2 && getRingerModeExternal() == 1 : getRingerModeExternal() != 0;
        }
        return false;
    }

    public int getVibrateSetting(int vibrateType) {
        if (this.mHasVibrator) {
            return (this.mVibrateSetting >> (vibrateType * 2)) & 3;
        }
        return 0;
    }

    public void setVibrateSetting(int vibrateType, int vibrateSetting) {
        if (this.mHasVibrator) {
            this.mVibrateSetting = AudioSystem.getValueForVibrateSetting(this.mVibrateSetting, vibrateType, vibrateSetting);
            broadcastVibrateSetting(vibrateType);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getModeOwnerPid() {
        try {
            int modeOwnerPid = this.mSetModeDeathHandlers.get(0).getPid();
            return modeOwnerPid;
        } catch (Exception e) {
            return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class SetModeDeathHandler implements IBinder.DeathRecipient {
        private IBinder mCb;
        private int mMode = 0;
        private int mPid;

        SetModeDeathHandler(IBinder cb, int pid) {
            this.mCb = cb;
            this.mPid = pid;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            int oldModeOwnerPid = 0;
            int newModeOwnerPid = 0;
            synchronized (AudioService.this.mDeviceBroker.mSetModeLock) {
                Log.w(AudioService.TAG, "setMode() client died");
                if (!AudioService.this.mSetModeDeathHandlers.isEmpty()) {
                    oldModeOwnerPid = AudioService.this.mSetModeDeathHandlers.get(0).getPid();
                }
                int index = AudioService.this.mSetModeDeathHandlers.indexOf(this);
                if (index >= 0) {
                    newModeOwnerPid = AudioService.this.setModeInt(0, this.mCb, this.mPid, AudioService.TAG);
                } else {
                    Log.w(AudioService.TAG, "unregistered setMode() client died");
                }
            }
            if (newModeOwnerPid != oldModeOwnerPid && newModeOwnerPid != 0) {
                AudioService.this.mDeviceBroker.postDisconnectBluetoothSco(newModeOwnerPid);
            }
        }

        public int getPid() {
            return this.mPid;
        }

        public void setMode(int mode) {
            this.mMode = mode;
        }

        public int getMode() {
            return this.mMode;
        }

        public IBinder getBinder() {
            return this.mCb;
        }
    }

    public void setMode(int mode, IBinder cb, String callingPackage) {
        int newModeOwnerPid;
        if (!checkAudioSettingsPermission("setMode()")) {
            return;
        }
        if (mode == 2 && this.mContext.checkCallingOrSelfPermission("android.permission.MODIFY_PHONE_STATE") != 0) {
            Log.w(TAG, "MODIFY_PHONE_STATE Permission Denial: setMode(MODE_IN_CALL) from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
        } else if (mode < -1 || mode >= 4) {
        } else {
            int oldModeOwnerPid = 0;
            synchronized (this.mDeviceBroker.mSetModeLock) {
                if (!this.mSetModeDeathHandlers.isEmpty()) {
                    oldModeOwnerPid = this.mSetModeDeathHandlers.get(0).getPid();
                }
                if (mode == -1) {
                    mode = this.mMode;
                }
                newModeOwnerPid = setModeInt(mode, cb, Binder.getCallingPid(), callingPackage);
            }
            if (newModeOwnerPid != oldModeOwnerPid && newModeOwnerPid != 0) {
                this.mDeviceBroker.postDisconnectBluetoothSco(newModeOwnerPid);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mDeviceBroker.mSetModeLock"})
    public int setModeInt(int mode, IBinder cb, int pid, String caller) {
        int actualMode;
        SetModeDeathHandler hdlr;
        IBinder cb2;
        int mode2;
        int status;
        int newModeOwnerPid;
        if (cb == null) {
            Log.e(TAG, "setModeInt() called with null binder");
            return 0;
        }
        SetModeDeathHandler hdlr2 = null;
        Iterator iter = this.mSetModeDeathHandlers.iterator();
        while (true) {
            if (!iter.hasNext()) {
                break;
            }
            SetModeDeathHandler h = iter.next();
            if (h.getPid() == pid) {
                hdlr2 = h;
                iter.remove();
                hdlr2.getBinder().unlinkToDeath(hdlr2, 0);
                break;
            }
        }
        int oldMode = this.mMode;
        IBinder cb3 = cb;
        int mode3 = mode;
        while (true) {
            actualMode = mode3;
            if (mode3 == 0) {
                if (this.mSetModeDeathHandlers.isEmpty()) {
                    cb2 = cb3;
                } else {
                    SetModeDeathHandler hdlr3 = this.mSetModeDeathHandlers.get(0);
                    hdlr2 = hdlr3;
                    IBinder cb4 = hdlr2.getBinder();
                    actualMode = hdlr2.getMode();
                    cb2 = cb4;
                }
            } else {
                if (hdlr2 == null) {
                    hdlr = new SetModeDeathHandler(cb3, pid);
                } else {
                    hdlr = hdlr2;
                }
                try {
                    cb3.linkToDeath(hdlr, 0);
                } catch (RemoteException e) {
                    Log.w(TAG, "setMode() could not link to " + cb3 + " binder death");
                }
                this.mSetModeDeathHandlers.add(0, hdlr);
                hdlr.setMode(mode3);
                hdlr2 = hdlr;
                cb2 = cb3;
            }
            if (actualMode != this.mMode) {
                long identity = Binder.clearCallingIdentity();
                int status2 = AudioSystem.setPhoneState(actualMode);
                Binder.restoreCallingIdentity(identity);
                if (status2 == 0) {
                    this.mMode = actualMode;
                } else {
                    if (hdlr2 != null) {
                        this.mSetModeDeathHandlers.remove(hdlr2);
                        cb2.unlinkToDeath(hdlr2, 0);
                    }
                    mode3 = 0;
                }
                mode2 = mode3;
                status = status2;
            } else {
                mode2 = mode3;
                status = 0;
            }
            if (status == 0 || this.mSetModeDeathHandlers.isEmpty()) {
                break;
            }
            cb3 = cb2;
            mode3 = mode2;
        }
        if (status == 0) {
            if (actualMode != 0) {
                if (this.mSetModeDeathHandlers.isEmpty()) {
                    Log.e(TAG, "setMode() different from MODE_NORMAL with empty mode client stack");
                } else {
                    int newModeOwnerPid2 = this.mSetModeDeathHandlers.get(0).getPid();
                    newModeOwnerPid = newModeOwnerPid2;
                    this.mModeLogger.log(new AudioServiceEvents.PhoneStateEvent(caller, pid, mode2, newModeOwnerPid, actualMode));
                    int streamType = getActiveStreamType(Integer.MIN_VALUE);
                    int device = getDeviceForStream(streamType);
                    int index = this.mStreamStates[mStreamVolumeAlias[streamType]].getIndex(device);
                    setStreamVolumeInt(mStreamVolumeAlias[streamType], index, device, true, caller);
                    updateStreamVolumeAlias(true, caller);
                    updateAbsVolumeMultiModeDevices(oldMode, actualMode);
                    return newModeOwnerPid;
                }
            }
            newModeOwnerPid = 0;
            this.mModeLogger.log(new AudioServiceEvents.PhoneStateEvent(caller, pid, mode2, newModeOwnerPid, actualMode));
            int streamType2 = getActiveStreamType(Integer.MIN_VALUE);
            int device2 = getDeviceForStream(streamType2);
            int index2 = this.mStreamStates[mStreamVolumeAlias[streamType2]].getIndex(device2);
            setStreamVolumeInt(mStreamVolumeAlias[streamType2], index2, device2, true, caller);
            updateStreamVolumeAlias(true, caller);
            updateAbsVolumeMultiModeDevices(oldMode, actualMode);
            return newModeOwnerPid;
        }
        return 0;
    }

    public int getMode() {
        return this.mMode;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class LoadSoundEffectReply {
        public int mStatus = 1;

        LoadSoundEffectReply() {
        }
    }

    private void loadTouchSoundAssetDefaults() {
        SOUND_EFFECT_FILES.add("Effect_Tick.ogg");
        for (int i = 0; i < 24; i++) {
            int[][] iArr = this.SOUND_EFFECT_FILES_MAP;
            iArr[i][0] = 0;
            iArr[i][1] = -1;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void loadTouchSoundAssets() {
        XmlResourceParser parser = null;
        if (SOUND_EFFECT_FILES.isEmpty()) {
            loadTouchSoundAssetDefaults();
            try {
                try {
                    try {
                        parser = this.mContext.getResources().getXml(18284545);
                        XmlUtils.beginDocument(parser, TAG_AUDIO_ASSETS);
                        String version = parser.getAttributeValue(null, "version");
                        boolean inTouchSoundsGroup = false;
                        if (ASSET_FILE_VERSION.equals(version)) {
                            while (true) {
                                XmlUtils.nextElement(parser);
                                String element = parser.getName();
                                if (element == null) {
                                    break;
                                } else if (element.equals(TAG_GROUP)) {
                                    String name = parser.getAttributeValue(null, "name");
                                    if (GROUP_TOUCH_SOUNDS.equals(name)) {
                                        inTouchSoundsGroup = true;
                                        break;
                                    }
                                }
                            }
                            while (inTouchSoundsGroup) {
                                XmlUtils.nextElement(parser);
                                String element2 = parser.getName();
                                if (element2 == null || !element2.equals(TAG_ASSET)) {
                                    break;
                                }
                                String id = parser.getAttributeValue(null, ATTR_ASSET_ID);
                                String file = parser.getAttributeValue(null, ATTR_ASSET_FILE);
                                try {
                                    Field field = AudioManager.class.getField(id);
                                    int fx = field.getInt(null);
                                    int i = SOUND_EFFECT_FILES.indexOf(file);
                                    if (i == -1) {
                                        i = SOUND_EFFECT_FILES.size();
                                        SOUND_EFFECT_FILES.add(file);
                                    }
                                    this.SOUND_EFFECT_FILES_MAP[fx][0] = i;
                                } catch (Exception e) {
                                    Log.w(TAG, "Invalid touch sound ID: " + id);
                                }
                            }
                        }
                    } catch (IOException e2) {
                        Log.w(TAG, "I/O exception reading touch sound assets", e2);
                        if (parser == null) {
                            return;
                        }
                    } catch (XmlPullParserException e3) {
                        Log.w(TAG, "XML parser exception reading touch sound assets", e3);
                        if (parser == null) {
                            return;
                        }
                    }
                } catch (Resources.NotFoundException e4) {
                    Log.w(TAG, "audio assets file not found", e4);
                    if (parser == null) {
                        return;
                    }
                }
                parser.close();
            } catch (Throwable th) {
                if (parser != null) {
                    parser.close();
                }
                throw th;
            }
        }
    }

    public void playSoundEffect(int effectType) {
        playSoundEffectVolume(effectType, -1.0f);
    }

    public void playSoundEffectVolume(int effectType, float volume) {
        if (isStreamMutedByRingerOrZenMode(1)) {
            return;
        }
        synchronized (this.mSystemSoundLock) {
            if (!this.mSystemSoundOn) {
                Log.d(TAG, "playSoundEffectVolume: mSystemSoundOn=false");
            } else if (effectType >= 24 || effectType < 0) {
                Log.w(TAG, "AudioService effectType value " + effectType + " out of range");
            } else {
                sendMsg(this.mAudioHandler, 5, 2, effectType, (int) (1000.0f * volume), null, 0);
            }
        }
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:33:? -> B:27:0x003b). Please submit an issue!!! */
    public boolean loadSoundEffects() {
        Throwable th;
        int attempts = 3;
        LoadSoundEffectReply reply = new LoadSoundEffectReply();
        synchronized (reply) {
            try {
                sendMsg(this.mAudioHandler, 7, 2, 0, 0, reply, 0);
                while (true) {
                    if (reply.mStatus != 1) {
                        break;
                    }
                    int attempts2 = attempts - 1;
                    if (attempts <= 0) {
                        attempts = attempts2;
                        break;
                    }
                    try {
                        try {
                            reply.wait(5000L);
                        } catch (Throwable th2) {
                            th = th2;
                            throw th;
                        }
                    } catch (InterruptedException e) {
                        Log.w(TAG, "loadSoundEffects Interrupted while waiting sound pool loaded.");
                    }
                    attempts = attempts2;
                }
                return reply.mStatus == 0;
            } catch (Throwable th3) {
                th = th3;
                throw th;
            }
        }
    }

    protected void scheduleLoadSoundEffects() {
        sendMsg(this.mAudioHandler, 7, 2, 0, 0, null, 0);
    }

    public void unloadSoundEffects() {
        sendMsg(this.mAudioHandler, 15, 2, 0, 0, null, 0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class SoundPoolListenerThread extends Thread {
        public SoundPoolListenerThread() {
            super("SoundPoolListenerThread");
        }

        @Override // java.lang.Thread, java.lang.Runnable
        public void run() {
            Looper.prepare();
            AudioService.this.mSoundPoolLooper = Looper.myLooper();
            synchronized (AudioService.this.mSoundEffectsLock) {
                if (AudioService.this.mSoundPool != null) {
                    AudioService.this.mSoundPoolCallBack = new SoundPoolCallback();
                    AudioService.this.mSoundPool.setOnLoadCompleteListener(AudioService.this.mSoundPoolCallBack);
                }
                AudioService.this.mSoundEffectsLock.notify();
            }
            Looper.loop();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class SoundPoolCallback implements SoundPool.OnLoadCompleteListener {
        List<Integer> mSamples;
        int mStatus;

        private SoundPoolCallback() {
            this.mStatus = 1;
            this.mSamples = new ArrayList();
        }

        public int status() {
            return this.mStatus;
        }

        public void setSamples(int[] samples) {
            for (int i = 0; i < samples.length; i++) {
                if (samples[i] > 0) {
                    this.mSamples.add(Integer.valueOf(samples[i]));
                }
            }
        }

        @Override // android.media.SoundPool.OnLoadCompleteListener
        public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
            synchronized (AudioService.this.mSoundEffectsLock) {
                int i = this.mSamples.indexOf(Integer.valueOf(sampleId));
                if (i >= 0) {
                    this.mSamples.remove(i);
                }
                if (status != 0 || this.mSamples.isEmpty()) {
                    this.mStatus = status;
                    AudioService.this.mSoundEffectsLock.notify();
                }
            }
        }
    }

    public void reloadAudioSettings() {
        readAudioSettings(false);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void readAudioSettings(boolean userSwitch) {
        readPersistedSettings();
        readUserRestrictions();
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int streamType = 0; streamType < numStreamTypes; streamType++) {
            VolumeStreamState streamState = this.mStreamStates[streamType];
            if (!userSwitch || mStreamVolumeAlias[streamType] != 3) {
                streamState.readSettings();
                synchronized (VolumeStreamState.class) {
                    if (streamState.mIsMuted && ((!isStreamAffectedByMute(streamType) && !isStreamMutedByRingerOrZenMode(streamType)) || this.mUseFixedVolume)) {
                        streamState.mIsMuted = false;
                    }
                }
                continue;
            }
        }
        int streamType2 = getRingerModeInternal();
        setRingerModeInt(streamType2, false);
        checkAllFixedVolumeDevices();
        checkAllAliasStreamVolumes();
        checkMuteAffectedStreams();
        synchronized (this.mSafeMediaVolumeStateLock) {
            this.mMusicActiveMs = MathUtils.constrain(Settings.Secure.getIntForUser(this.mContentResolver, "unsafe_volume_music_active_ms", 0, -2), 0, (int) UNSAFE_VOLUME_MUSIC_ACTIVE_MS_MAX);
            if (this.mSafeMediaVolumeState == 3) {
                enforceSafeMediaVolume(TAG);
            }
        }
    }

    public void setSpeakerphoneOn(boolean on) {
        if (!checkAudioSettingsPermission("setSpeakerphoneOn()")) {
            return;
        }
        if (this.mContext.checkCallingOrSelfPermission("android.permission.MODIFY_PHONE_STATE") != 0) {
            synchronized (this.mSetModeDeathHandlers) {
                Iterator<SetModeDeathHandler> it = this.mSetModeDeathHandlers.iterator();
                while (it.hasNext()) {
                    SetModeDeathHandler h = it.next();
                    if (h.getMode() == 2) {
                        Log.w(TAG, "getMode is call, Permission Denial: setSpeakerphoneOn from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
                        return;
                    }
                }
            }
        }
        String eventSource = "setSpeakerphoneOn(" + on + ") from u/pid:" + Binder.getCallingUid() + SliceClientPermissions.SliceAuthority.DELIMITER + Binder.getCallingPid();
        boolean stateChanged = this.mDeviceBroker.setSpeakerphoneOn(on, eventSource);
        if (stateChanged) {
            long ident = Binder.clearCallingIdentity();
            try {
                this.mContext.sendBroadcastAsUser(new Intent("android.media.action.SPEAKERPHONE_STATE_CHANGED").setFlags(1073741824), UserHandle.ALL);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public boolean isSpeakerphoneOn() {
        return this.mDeviceBroker.isSpeakerphoneOn();
    }

    public void setBluetoothScoOn(boolean on) {
        if (!checkAudioSettingsPermission("setBluetoothScoOn()")) {
            return;
        }
        if (UserHandle.getCallingAppId() >= 10000) {
            this.mDeviceBroker.setBluetoothScoOnByApp(on);
            return;
        }
        String eventSource = "setBluetoothScoOn(" + on + ") from u/pid:" + Binder.getCallingUid() + SliceClientPermissions.SliceAuthority.DELIMITER + Binder.getCallingPid();
        this.mDeviceBroker.setBluetoothScoOn(on, eventSource);
    }

    public boolean isBluetoothScoOn() {
        return this.mDeviceBroker.isBluetoothScoOnForApp();
    }

    public void setBluetoothA2dpOn(boolean on) {
        String eventSource = "setBluetoothA2dpOn(" + on + ") from u/pid:" + Binder.getCallingUid() + SliceClientPermissions.SliceAuthority.DELIMITER + Binder.getCallingPid();
        this.mDeviceBroker.setBluetoothA2dpOn_Async(on, eventSource);
    }

    public boolean isBluetoothA2dpOn() {
        return this.mDeviceBroker.isBluetoothA2dpOn();
    }

    public void startBluetoothSco(IBinder cb, int targetSdkVersion) {
        int scoAudioMode = targetSdkVersion < 18 ? 0 : -1;
        String eventSource = "startBluetoothSco()) from u/pid:" + Binder.getCallingUid() + SliceClientPermissions.SliceAuthority.DELIMITER + Binder.getCallingPid();
        startBluetoothScoInt(cb, scoAudioMode, eventSource);
    }

    public void startBluetoothScoVirtualCall(IBinder cb) {
        String eventSource = "startBluetoothScoVirtualCall()) from u/pid:" + Binder.getCallingUid() + SliceClientPermissions.SliceAuthority.DELIMITER + Binder.getCallingPid();
        startBluetoothScoInt(cb, 0, eventSource);
    }

    void startBluetoothScoInt(IBinder cb, int scoAudioMode, String eventSource) {
        if (!checkAudioSettingsPermission("startBluetoothSco()") || !this.mSystemReady) {
            return;
        }
        synchronized (this.mDeviceBroker.mSetModeLock) {
            this.mDeviceBroker.startBluetoothScoForClient_Sync(cb, scoAudioMode, eventSource);
        }
    }

    public void stopBluetoothSco(IBinder cb) {
        if (!checkAudioSettingsPermission("stopBluetoothSco()") || !this.mSystemReady) {
            return;
        }
        String eventSource = "stopBluetoothSco()) from u/pid:" + Binder.getCallingUid() + SliceClientPermissions.SliceAuthority.DELIMITER + Binder.getCallingPid();
        synchronized (this.mDeviceBroker.mSetModeLock) {
            this.mDeviceBroker.stopBluetoothScoForClient_Sync(cb, eventSource);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ContentResolver getContentResolver() {
        return this.mContentResolver;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onCheckMusicActive(String caller) {
        synchronized (this.mSafeMediaVolumeStateLock) {
            if (this.mSafeMediaVolumeState == 2) {
                int device = getDeviceForStream(3);
                if ((67108876 & device) != 0) {
                    sendMsg(this.mAudioHandler, 11, 0, 0, 0, caller, MUSIC_ACTIVE_POLL_PERIOD_MS);
                    int index = this.mStreamStates[3].getIndex(device);
                    if (AudioSystem.isStreamActive(3, 0) && index > safeMediaVolumeIndex(device)) {
                        this.mMusicActiveMs += MUSIC_ACTIVE_POLL_PERIOD_MS;
                        if (this.mMusicActiveMs > UNSAFE_VOLUME_MUSIC_ACTIVE_MS_MAX) {
                            setSafeMediaVolumeEnabled(true, caller);
                            this.mMusicActiveMs = 0;
                        }
                        saveMusicActiveMs();
                    }
                }
            }
        }
    }

    private void saveMusicActiveMs() {
        this.mAudioHandler.obtainMessage(17, this.mMusicActiveMs, 0).sendToTarget();
    }

    private int getSafeUsbMediaVolumeIndex() {
        int min = MIN_STREAM_VOLUME[3];
        int max = MAX_STREAM_VOLUME[3];
        this.mSafeUsbMediaVolumeDbfs = this.mContext.getResources().getInteger(17694879) / 100.0f;
        while (true) {
            if (Math.abs(max - min) <= 1) {
                break;
            }
            int index = (max + min) / 2;
            float gainDB = AudioSystem.getStreamVolumeDB(3, index, 67108864);
            if (Float.isNaN(gainDB)) {
                break;
            }
            float f = this.mSafeUsbMediaVolumeDbfs;
            if (gainDB == f) {
                min = index;
                break;
            } else if (gainDB < f) {
                min = index;
            } else {
                max = index;
            }
        }
        return min * 10;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onConfigureSafeVolume(boolean force, String caller) {
        boolean safeMediaVolumeEnabled;
        int persistedState;
        synchronized (this.mSafeMediaVolumeStateLock) {
            int mcc = this.mContext.getResources().getConfiguration().mcc;
            if (this.mMcc != mcc || (this.mMcc == 0 && force)) {
                this.mSafeMediaVolumeIndex = this.mContext.getResources().getInteger(17694878) * 10;
                this.mSafeUsbMediaVolumeIndex = getSafeUsbMediaVolumeIndex();
                if (!SystemProperties.getBoolean("audio.safemedia.force", false) && !this.mContext.getResources().getBoolean(17891508)) {
                    safeMediaVolumeEnabled = false;
                    boolean safeMediaVolumeBypass = SystemProperties.getBoolean("audio.safemedia.bypass", false);
                    if (!safeMediaVolumeEnabled && !safeMediaVolumeBypass) {
                        persistedState = 3;
                        if (this.mSafeMediaVolumeState != 2) {
                            if (this.mMusicActiveMs == 0) {
                                this.mSafeMediaVolumeState = 3;
                                enforceSafeMediaVolume(caller);
                            } else {
                                this.mSafeMediaVolumeState = 2;
                            }
                        }
                    } else {
                        this.mSafeMediaVolumeState = 1;
                        persistedState = 1;
                    }
                    this.mMcc = mcc;
                    sendMsg(this.mAudioHandler, 14, 2, persistedState, 0, null, 0);
                }
                safeMediaVolumeEnabled = true;
                boolean safeMediaVolumeBypass2 = SystemProperties.getBoolean("audio.safemedia.bypass", false);
                if (!safeMediaVolumeEnabled) {
                }
                this.mSafeMediaVolumeState = 1;
                persistedState = 1;
                this.mMcc = mcc;
                sendMsg(this.mAudioHandler, 14, 2, persistedState, 0, null, 0);
            }
        }
    }

    private int checkForRingerModeChange(int oldIndex, int direction, int step, boolean isMuted, String caller, int flags) {
        int result = 1;
        if (isPlatformTelevision() || this.mIsSingleVolume) {
            return 1;
        }
        int ringerMode = getRingerModeInternal();
        if (ringerMode == 0) {
            if (this.mIsSingleVolume && direction == -1 && oldIndex >= step * 2 && isMuted) {
                ringerMode = 2;
            } else if (direction == 1 || direction == 101 || direction == 100) {
                if (!this.mVolumePolicy.volumeUpToExitSilent) {
                    result = 1 | 128;
                } else {
                    ringerMode = (this.mHasVibrator && direction == 1) ? 1 : 2;
                }
            }
            result &= -2;
        } else if (ringerMode != 1) {
            if (ringerMode == 2) {
                if (direction == -1) {
                    if (this.mHasVibrator) {
                        if (step <= oldIndex && oldIndex < step * 2) {
                            ringerMode = 1;
                            this.mLoweredFromNormalToVibrateTime = SystemClock.uptimeMillis();
                        }
                    } else if (oldIndex == step && this.mVolumePolicy.volumeDownToEnterSilent) {
                        ringerMode = 0;
                    }
                } else if (this.mIsSingleVolume && (direction == 101 || direction == -100)) {
                    if (this.mHasVibrator) {
                        ringerMode = 1;
                    } else {
                        ringerMode = 0;
                    }
                    result = 1 & (-2);
                }
            } else {
                Log.e(TAG, "checkForRingerModeChange() wrong ringer mode: " + ringerMode);
            }
        } else if (!this.mHasVibrator) {
            Log.e(TAG, "checkForRingerModeChange() current ringer mode is vibratebut no vibrator is present");
        } else {
            if (direction == -1) {
                if (this.mIsSingleVolume && oldIndex >= step * 2 && isMuted) {
                    ringerMode = 2;
                } else if (this.mPrevVolDirection != -1) {
                    if (this.mVolumePolicy.volumeDownToEnterSilent) {
                        long diff = SystemClock.uptimeMillis() - this.mLoweredFromNormalToVibrateTime;
                        if (diff > this.mVolumePolicy.vibrateToSilentDebounce && this.mRingerModeDelegate.canVolumeDownEnterSilent()) {
                            ringerMode = 0;
                        }
                    } else {
                        result = 1 | 2048;
                    }
                }
            } else if (direction == 1 || direction == 101 || direction == 100) {
                ringerMode = 2;
            }
            result &= -2;
        }
        if (isAndroidNPlus(caller) && wouldToggleZenMode(ringerMode) && !this.mNm.isNotificationPolicyAccessGrantedForPackage(caller) && (flags & 4096) == 0) {
            throw new SecurityException("Not allowed to change Do Not Disturb state");
        }
        setRingerMode(ringerMode, "AS.AudioService.checkForRingerModeChange", false);
        this.mPrevVolDirection = direction;
        return result;
    }

    public boolean isStreamAffectedByRingerMode(int streamType) {
        return (this.mRingerModeAffectedStreams & (1 << streamType)) != 0;
    }

    private boolean shouldZenMuteStream(int streamType) {
        if (this.mNm.getZenMode() != 1) {
            return false;
        }
        NotificationManager.Policy zenPolicy = this.mNm.getConsolidatedNotificationPolicy();
        boolean muteAlarms = (zenPolicy.priorityCategories & 32) == 0;
        boolean muteMedia = (zenPolicy.priorityCategories & 64) == 0;
        boolean muteSystem = (zenPolicy.priorityCategories & 128) == 0;
        boolean muteNotificationAndRing = ZenModeConfig.areAllPriorityOnlyNotificationZenSoundsMuted(zenPolicy);
        return (muteAlarms && isAlarm(streamType)) || (muteMedia && isMedia(streamType)) || ((muteSystem && isSystem(streamType)) || (muteNotificationAndRing && isNotificationOrRinger(streamType)));
    }

    private boolean isStreamMutedByRingerOrZenMode(int streamType) {
        return (this.mRingerAndZenModeMutedStreams & (1 << streamType)) != 0;
    }

    private boolean updateZenModeAffectedStreams() {
        int zenModeAffectedStreams = 0;
        if (this.mSystemReady && this.mNm.getZenMode() == 1) {
            NotificationManager.Policy zenPolicy = this.mNm.getConsolidatedNotificationPolicy();
            if ((zenPolicy.priorityCategories & 32) == 0) {
                zenModeAffectedStreams = 0 | 16;
            }
            if ((zenPolicy.priorityCategories & 64) == 0) {
                zenModeAffectedStreams |= 8;
            }
            if ((zenPolicy.priorityCategories & 128) == 0) {
                zenModeAffectedStreams |= 2;
            }
            if (ZenModeConfig.areAllPriorityOnlyNotificationZenSoundsMuted(zenPolicy)) {
                zenModeAffectedStreams = zenModeAffectedStreams | 32 | 4;
            }
        }
        if (this.mZenModeAffectedStreams != zenModeAffectedStreams) {
            this.mZenModeAffectedStreams = zenModeAffectedStreams;
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mSettingsLock"})
    public boolean updateRingerAndZenModeAffectedStreams() {
        int ringerModeAffectedStreams;
        int ringerModeAffectedStreams2;
        boolean updatedZenModeAffectedStreams = updateZenModeAffectedStreams();
        int ringerModeAffectedStreams3 = Settings.System.getIntForUser(this.mContentResolver, "mode_ringer_streams_affected", 166, -2);
        if (this.mIsSingleVolume) {
            ringerModeAffectedStreams3 = 0;
        } else {
            AudioManagerInternal.RingerModeDelegate ringerModeDelegate = this.mRingerModeDelegate;
            if (ringerModeDelegate != null) {
                ringerModeAffectedStreams3 = ringerModeDelegate.getRingerModeAffectedStreams(ringerModeAffectedStreams3);
            }
        }
        if (this.mCameraSoundForced) {
            ringerModeAffectedStreams = ringerModeAffectedStreams3 & (-129);
        } else {
            ringerModeAffectedStreams = ringerModeAffectedStreams3 | 128;
        }
        if (mStreamVolumeAlias[8] == 2) {
            ringerModeAffectedStreams2 = ringerModeAffectedStreams | 256;
        } else {
            ringerModeAffectedStreams2 = ringerModeAffectedStreams & (-257);
        }
        if (ringerModeAffectedStreams2 != this.mRingerModeAffectedStreams) {
            Settings.System.putIntForUser(this.mContentResolver, "mode_ringer_streams_affected", ringerModeAffectedStreams2, -2);
            this.mRingerModeAffectedStreams = ringerModeAffectedStreams2;
            return true;
        }
        return updatedZenModeAffectedStreams;
    }

    public boolean isStreamAffectedByMute(int streamType) {
        return (this.mMuteAffectedStreams & (1 << streamType)) != 0;
    }

    private void ensureValidDirection(int direction) {
        if (direction != -100 && direction != -1 && direction != 0 && direction != 1 && direction != 100 && direction != 101) {
            throw new IllegalArgumentException("Bad direction " + direction);
        }
    }

    private void ensureValidStreamType(int streamType) {
        if (streamType < 0 || streamType >= this.mStreamStates.length) {
            throw new IllegalArgumentException("Bad stream type " + streamType);
        }
    }

    private boolean isMuteAdjust(int adjust) {
        return adjust == -100 || adjust == 100 || adjust == 101;
    }

    @VisibleForTesting
    public boolean isInCommunication() {
        return getMode() == 2;
    }

    private boolean wasStreamActiveRecently(int stream, int delay_ms) {
        return AudioSystem.isStreamActive(stream, delay_ms) || AudioSystem.isStreamActiveRemotely(stream, delay_ms);
    }

    public int lockActiveStream(boolean lock) {
        if (lock) {
            if (mCurActiveStream == -1) {
                mCurActiveStream = getActiveStreamType(Integer.MIN_VALUE);
            }
        } else {
            mCurActiveStream = -1;
        }
        mActiveStreamStatus = lock;
        Log.v(TAG, "lockActiveStream: lock[ " + lock + " ] ActiveStream is " + mActiveStreamStatus);
        return mCurActiveStream;
    }

    private int getActiveStreamType(int suggestedStreamType) {
        if (this.mIsSingleVolume && suggestedStreamType == Integer.MIN_VALUE) {
            return 3;
        }
        int i = this.mPlatformType;
        if (mXuiVolPolicy == 1 && mActiveStreamStatus && mCurActiveStream != -1) {
            Log.d(TAG, "getActiveStreamType: Stream Locked mode - curActiveStr is " + mCurActiveStream);
            return mCurActiveStream;
        } else if ((isBtCallOn() && getBtCallMode() == 1) || getBtCallOnFlag() == 3) {
            Log.d(TAG, "getActiveStreamType: is in BT Call");
            return 6;
        } else if (suggestedStreamType == Integer.MIN_VALUE) {
            if (mXuiVolPolicy != 2 && AudioSystem.isStreamActive(9, sStreamOverrideDelayMs)) {
                Log.v(TAG, "getActiveStreamType: Forcing STREAM_TTS");
                return 9;
            } else if (mXuiVolPolicy != 2 && AudioSystem.isStreamActive(10, sStreamOverrideDelayMs)) {
                Log.v(TAG, "getActiveStreamType: Forcing STREAM_ACCESSIBILITY");
                return 10;
            } else if (avasStreamEnable) {
                Log.v(TAG, "getActiveStreamType: Forcing STREAM_AVAS");
                return 11;
            } else {
                Log.v(TAG, "getActiveStreamType: Forcing DEFAULT_VOL_STREAM_NO_PLAYBACK(3) b/c default");
                return 3;
            }
        } else {
            Log.v(TAG, "getActiveStreamType: Returning suggested type " + suggestedStreamType);
            return suggestedStreamType;
        }
    }

    private void broadcastRingerMode(String action, int ringerMode) {
        Intent broadcast = new Intent(action);
        broadcast.putExtra("android.media.EXTRA_RINGER_MODE", ringerMode);
        broadcast.addFlags(603979776);
        sendStickyBroadcastToAll(broadcast);
    }

    private void broadcastVibrateSetting(int vibrateType) {
        if (this.mActivityManagerInternal.isSystemReady()) {
            Intent broadcast = new Intent("android.media.VIBRATE_SETTING_CHANGED");
            broadcast.putExtra("android.media.EXTRA_VIBRATE_TYPE", vibrateType);
            broadcast.putExtra("android.media.EXTRA_VIBRATE_SETTING", getVibrateSetting(vibrateType));
            sendBroadcastToAll(broadcast);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void queueMsgUnderWakeLock(Handler handler, int msg, int arg1, int arg2, Object obj, int delay) {
        long ident = Binder.clearCallingIdentity();
        this.mAudioEventWakeLock.acquire();
        Binder.restoreCallingIdentity(ident);
        sendMsg(handler, msg, 2, arg1, arg2, obj, delay);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void sendMsg(Handler handler, int msg, int existingMsgPolicy, int arg1, int arg2, Object obj, int delay) {
        if (existingMsgPolicy == 0) {
            handler.removeMessages(msg);
        } else if (existingMsgPolicy == 1 && handler.hasMessages(msg)) {
            return;
        }
        long time = SystemClock.uptimeMillis() + delay;
        handler.sendMessageAtTime(handler.obtainMessage(msg, arg1, arg2, obj), time);
    }

    boolean checkAudioSettingsPermission(String method) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.MODIFY_AUDIO_SETTINGS") == 0) {
            return true;
        }
        String msg = "Audio Settings Permission Denial: " + method + " from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid();
        Log.w(TAG, msg);
        return false;
    }

    @VisibleForTesting
    public int getDeviceForStream(int stream) {
        int device = getDevicesForStream(stream);
        if (((device - 1) & device) != 0) {
            if ((device & 2) != 0) {
                return 2;
            }
            if ((262144 & device) != 0) {
                return 262144;
            }
            if ((524288 & device) != 0) {
                return DumpState.DUMP_FROZEN;
            }
            if ((2097152 & device) != 0) {
                return DumpState.DUMP_COMPILER_STATS;
            }
            return device & 896;
        }
        return device;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getDevicesForStream(int stream) {
        return getDevicesForStream(stream, true);
    }

    private int getDevicesForStream(int stream, boolean checkOthers) {
        int observeDevicesForStream_syncVSS;
        ensureValidStreamType(stream);
        synchronized (VolumeStreamState.class) {
            observeDevicesForStream_syncVSS = this.mStreamStates[stream].observeDevicesForStream_syncVSS(checkOthers);
        }
        return observeDevicesForStream_syncVSS;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void observeDevicesForStreams(int skipStream) {
        synchronized (VolumeStreamState.class) {
            for (int stream = 0; stream < this.mStreamStates.length; stream++) {
                if (stream != skipStream) {
                    this.mStreamStates[stream].observeDevicesForStream_syncVSS(false);
                }
            }
        }
    }

    @VisibleForTesting
    public void postObserveDevicesForAllStreams() {
        sendMsg(this.mAudioHandler, MSG_OBSERVE_DEVICES_FOR_ALL_STREAMS, 2, 0, 0, null, 0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onObserveDevicesForAllStreams() {
        observeDevicesForStreams(-1);
    }

    public void setWiredDeviceConnectionState(int type, int state, String address, String name, String caller) {
        if (state != 1 && state != 0) {
            throw new IllegalArgumentException("Invalid state " + state);
        }
        this.mDeviceBroker.setWiredDeviceConnectionState(type, state, address, name, caller);
    }

    public void setBluetoothHearingAidDeviceConnectionState(BluetoothDevice device, int state, boolean suppressNoisyIntent, int musicDevice) {
        if (device == null) {
            throw new IllegalArgumentException("Illegal null device");
        }
        if (state != 2 && state != 0) {
            throw new IllegalArgumentException("Illegal BluetoothProfile state for device  (dis)connection, got " + state);
        }
        if (state == 2) {
            this.mPlaybackMonitor.registerPlaybackCallback(this.mVoiceActivityMonitor, true);
        } else {
            this.mPlaybackMonitor.unregisterPlaybackCallback(this.mVoiceActivityMonitor);
        }
        this.mDeviceBroker.postBluetoothHearingAidDeviceConnectionState(device, state, suppressNoisyIntent, musicDevice, "AudioService");
    }

    public void setBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(BluetoothDevice device, int state, int profile, boolean suppressNoisyIntent, int a2dpVolume) {
        if (device == null) {
            throw new IllegalArgumentException("Illegal null device");
        }
        if (state != 2 && state != 0) {
            throw new IllegalArgumentException("Illegal BluetoothProfile state for device  (dis)connection, got " + state);
        }
        this.mDeviceBroker.postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(device, state, profile, suppressNoisyIntent, a2dpVolume);
    }

    public void handleBluetoothA2dpDeviceConfigChange(BluetoothDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("Illegal null device");
        }
        this.mDeviceBroker.postBluetoothA2dpDeviceConfigChange(device);
    }

    @VisibleForTesting
    public void postAccessoryPlugMediaUnmute(int newDevice) {
        sendMsg(this.mAudioHandler, 21, 2, newDevice, 0, null, 0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onAccessoryPlugMediaUnmute(int newDevice) {
        Log.i(TAG, String.format("onAccessoryPlugMediaUnmute newDevice=%d [%s]", Integer.valueOf(newDevice), AudioSystem.getOutputDeviceName(newDevice)));
        if (this.mNm.getZenMode() != 2 && (DEVICE_MEDIA_UNMUTED_ON_PLUG & newDevice) != 0 && this.mStreamStates[3].mIsMuted && this.mStreamStates[3].getIndex(newDevice) != 0 && (AudioSystem.getDevicesForStream(3) & newDevice) != 0) {
            Log.i(TAG, String.format(" onAccessoryPlugMediaUnmute unmuting device=%d [%s]", Integer.valueOf(newDevice), AudioSystem.getOutputDeviceName(newDevice)));
            this.mStreamStates[3].mute(false);
        }
    }

    public boolean hasHapticChannels(Uri uri) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(this.mContext, uri, (Map<String, String>) null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                if (format.containsKey("haptic-channel-count") && format.getInteger("haptic-channel-count") > 0) {
                    return true;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "hasHapticChannels failure:" + e);
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class VolumeStreamState {
        private final SparseIntArray mIndexMap;
        private int mIndexMax;
        private int mIndexMin;
        private boolean mIsMuted;
        private int mObservedDevices;
        private final Intent mStreamDevicesChanged;
        private final int mStreamType;
        private final Intent mVolumeChanged;
        private String mVolumeIndexSettingName;

        private VolumeStreamState(String settingName, int streamType) {
            this.mIndexMap = new SparseIntArray(8);
            this.mVolumeIndexSettingName = settingName;
            this.mStreamType = streamType;
            this.mIndexMin = AudioService.MIN_STREAM_VOLUME[streamType] * 10;
            this.mIndexMax = AudioService.MAX_STREAM_VOLUME[streamType] * 10;
            AudioSystem.initStreamVolume(streamType, this.mIndexMin / 10, this.mIndexMax / 10);
            readSettings();
            this.mVolumeChanged = new Intent("android.media.VOLUME_CHANGED_ACTION");
            this.mVolumeChanged.putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", this.mStreamType);
            this.mStreamDevicesChanged = new Intent("android.media.STREAM_DEVICES_CHANGED_ACTION");
            this.mStreamDevicesChanged.putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", this.mStreamType);
        }

        public int observeDevicesForStream_syncVSS(boolean checkOthers) {
            int devices = AudioSystem.getDevicesForStream(this.mStreamType);
            if (devices == this.mObservedDevices) {
                return devices;
            }
            int prevDevices = this.mObservedDevices;
            this.mObservedDevices = devices;
            if (checkOthers) {
                AudioService.this.observeDevicesForStreams(this.mStreamType);
            }
            int[] iArr = AudioService.mStreamVolumeAlias;
            int i = this.mStreamType;
            if (iArr[i] == i) {
                EventLogTags.writeStreamDevicesChanged(i, prevDevices, devices);
            }
            AudioService.this.sendBroadcastToAll(this.mStreamDevicesChanged.putExtra("android.media.EXTRA_PREV_VOLUME_STREAM_DEVICES", prevDevices).putExtra("android.media.EXTRA_VOLUME_STREAM_DEVICES", devices));
            return devices;
        }

        public String getSettingNameForDevice(int device) {
            if (!hasValidSettingsName()) {
                return null;
            }
            String suffix = AudioSystem.getOutputDeviceName(device);
            if (suffix.isEmpty()) {
                return this.mVolumeIndexSettingName;
            }
            return this.mVolumeIndexSettingName + "_" + suffix;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean hasValidSettingsName() {
            String str = this.mVolumeIndexSettingName;
            return (str == null || str.isEmpty()) ? false : true;
        }

        public void readSettings() {
            int defaultIndex;
            int index;
            synchronized (AudioService.this.mSettingsLock) {
                synchronized (VolumeStreamState.class) {
                    if (AudioService.this.mUseFixedVolume) {
                        this.mIndexMap.put(1073741824, this.mIndexMax);
                        return;
                    }
                    if (this.mStreamType != 1 && this.mStreamType != 7) {
                        synchronized (VolumeStreamState.class) {
                            int remainingDevices = 1342177279;
                            int i = 0;
                            while (remainingDevices != 0) {
                                int device = 1 << i;
                                if ((device & remainingDevices) != 0) {
                                    remainingDevices &= ~device;
                                    if (device != 1073741824) {
                                        defaultIndex = -1;
                                    } else {
                                        defaultIndex = AudioSystem.DEFAULT_STREAM_VOLUME[this.mStreamType];
                                    }
                                    if (!hasValidSettingsName()) {
                                        index = defaultIndex;
                                    } else {
                                        String name = getSettingNameForDevice(device);
                                        index = Settings.System.getIntForUser(AudioService.this.mContentResolver, name, defaultIndex, -2);
                                    }
                                    if (index != -1) {
                                        this.mIndexMap.put(device, getValidIndex(index * 10));
                                    }
                                }
                                i++;
                            }
                        }
                        return;
                    }
                    int index2 = AudioSystem.DEFAULT_STREAM_VOLUME[this.mStreamType] * 10;
                    if (AudioService.this.mCameraSoundForced) {
                        index2 = this.mIndexMax;
                    }
                    this.mIndexMap.put(1073741824, index2);
                }
            }
        }

        private int getAbsoluteVolumeIndex(int index) {
            if (index == 0) {
                return 0;
            }
            if (index > 0 && index <= 3) {
                return ((int) (this.mIndexMax * AudioService.this.mPrescaleAbsoluteVolume[index - 1])) / 10;
            }
            return (this.mIndexMax + 5) / 10;
        }

        private void setStreamVolumeIndex(int index, int device) {
            if (this.mStreamType == 6 && index == 0 && !this.mIsMuted) {
                index = 1;
            }
            AudioSystem.setStreamVolumeIndexAS(this.mStreamType, index, device);
        }

        void applyDeviceVolume_syncVSS(int device, boolean isAvrcpAbsVolSupported) {
            int index;
            if (this.mIsMuted) {
                index = 0;
            } else {
                int index2 = device & 896;
                if (index2 != 0 && isAvrcpAbsVolSupported) {
                    index = getAbsoluteVolumeIndex((getIndex(device) + 5) / 10);
                } else if ((AudioService.this.mFullVolumeDevices & device) != 0) {
                    index = (this.mIndexMax + 5) / 10;
                } else if ((134217728 & device) != 0) {
                    index = (this.mIndexMax + 5) / 10;
                } else {
                    int index3 = getIndex(device);
                    index = (index3 + 5) / 10;
                }
            }
            setStreamVolumeIndex(index, device);
        }

        public void applyAllVolumes() {
            int index;
            int index2;
            boolean isAvrcpAbsVolSupported = AudioService.this.mDeviceBroker.isAvrcpAbsoluteVolumeSupported();
            synchronized (VolumeStreamState.class) {
                for (int i = 0; i < this.mIndexMap.size(); i++) {
                    int device = this.mIndexMap.keyAt(i);
                    if (device != 1073741824) {
                        if (this.mIsMuted) {
                            index2 = 0;
                        } else {
                            int index3 = device & 896;
                            if (index3 != 0 && isAvrcpAbsVolSupported) {
                                index2 = getAbsoluteVolumeIndex((getIndex(device) + 5) / 10);
                            } else if ((AudioService.this.mFullVolumeDevices & device) != 0) {
                                index2 = (this.mIndexMax + 5) / 10;
                            } else if ((134217728 & device) != 0) {
                                index2 = (this.mIndexMax + 5) / 10;
                            } else {
                                index2 = (this.mIndexMap.valueAt(i) + 5) / 10;
                            }
                        }
                        setStreamVolumeIndex(index2, device);
                    }
                }
                if (this.mIsMuted) {
                    index = 0;
                } else {
                    int index4 = getIndex(1073741824);
                    index = (index4 + 5) / 10;
                }
                setStreamVolumeIndex(index, 1073741824);
            }
        }

        public boolean adjustIndex(int deltaIndex, int device, String caller) {
            return setIndex(getIndex(device) + deltaIndex, device, caller);
        }

        public boolean setIndex(int index, int device, String caller) {
            int oldIndex;
            int index2;
            boolean changed;
            synchronized (AudioService.this.mSettingsLock) {
                synchronized (VolumeStreamState.class) {
                    oldIndex = getIndex(device);
                    index2 = getValidIndex(index);
                    if (this.mStreamType == 7 && AudioService.this.mCameraSoundForced) {
                        index2 = this.mIndexMax;
                    }
                    this.mIndexMap.put(device, index2);
                    boolean isCurrentDevice = true;
                    changed = oldIndex != index2;
                    if (device != AudioService.this.getDeviceForStream(this.mStreamType)) {
                        isCurrentDevice = false;
                    }
                    int numStreamTypes = AudioSystem.getNumStreamTypes();
                    for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                        VolumeStreamState aliasStreamState = AudioService.this.mStreamStates[streamType];
                        if (streamType != this.mStreamType && AudioService.mStreamVolumeAlias[streamType] == this.mStreamType && (changed || !aliasStreamState.hasIndexForDevice(device))) {
                            int scaledIndex = AudioService.this.rescaleIndex(index2, this.mStreamType, streamType);
                            aliasStreamState.setIndex(scaledIndex, device, caller);
                            if (isCurrentDevice) {
                                aliasStreamState.setIndex(scaledIndex, AudioService.this.getDeviceForStream(streamType), caller);
                            }
                        }
                    }
                    if (changed && this.mStreamType == 2 && device == 2) {
                        for (int i = 0; i < this.mIndexMap.size(); i++) {
                            int otherDevice = this.mIndexMap.keyAt(i);
                            if ((otherDevice & HdmiCecKeycode.UI_BROADCAST_DIGITAL_CABLE) != 0) {
                                this.mIndexMap.put(otherDevice, index2);
                            }
                        }
                    }
                }
            }
            if (changed) {
                int oldIndex2 = (oldIndex + 5) / 10;
                int index3 = (index2 + 5) / 10;
                int[] iArr = AudioService.mStreamVolumeAlias;
                int i2 = this.mStreamType;
                if (iArr[i2] == i2) {
                    if (caller == null) {
                        Log.w(AudioService.TAG, "No caller for volume_changed event", new Throwable());
                    }
                    EventLogTags.writeVolumeChanged(this.mStreamType, oldIndex2, index3, this.mIndexMax / 10, caller);
                }
                this.mVolumeChanged.putExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", index3);
                this.mVolumeChanged.putExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", oldIndex2);
                this.mVolumeChanged.putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE_ALIAS", AudioService.mStreamVolumeAlias[this.mStreamType]);
                AudioService.this.sendBroadcastToAll(this.mVolumeChanged);
            }
            return changed;
        }

        public int getIndex(int device) {
            int index;
            synchronized (VolumeStreamState.class) {
                index = this.mIndexMap.get(device, -1);
                if (index == -1) {
                    index = this.mIndexMap.get(1073741824);
                }
            }
            return index;
        }

        public boolean hasIndexForDevice(int device) {
            boolean z;
            synchronized (VolumeStreamState.class) {
                z = this.mIndexMap.get(device, -1) != -1;
            }
            return z;
        }

        public int getMaxIndex() {
            return this.mIndexMax;
        }

        public int getMinIndex() {
            return this.mIndexMin;
        }

        @GuardedBy({"VolumeStreamState.class"})
        public void setAllIndexes(VolumeStreamState srcStream, String caller) {
            if (this.mStreamType == srcStream.mStreamType) {
                return;
            }
            int srcStreamType = srcStream.getStreamType();
            int index = srcStream.getIndex(1073741824);
            int index2 = AudioService.this.rescaleIndex(index, srcStreamType, this.mStreamType);
            for (int i = 0; i < this.mIndexMap.size(); i++) {
                SparseIntArray sparseIntArray = this.mIndexMap;
                sparseIntArray.put(sparseIntArray.keyAt(i), index2);
            }
            SparseIntArray srcMap = srcStream.mIndexMap;
            for (int i2 = 0; i2 < srcMap.size(); i2++) {
                int device = srcMap.keyAt(i2);
                int index3 = srcMap.valueAt(i2);
                setIndex(AudioService.this.rescaleIndex(index3, srcStreamType, this.mStreamType), device, caller);
            }
        }

        @GuardedBy({"VolumeStreamState.class"})
        public void setAllIndexesToMax() {
            for (int i = 0; i < this.mIndexMap.size(); i++) {
                SparseIntArray sparseIntArray = this.mIndexMap;
                sparseIntArray.put(sparseIntArray.keyAt(i), this.mIndexMax);
            }
        }

        public void mute(boolean state) {
            boolean changed = false;
            synchronized (VolumeStreamState.class) {
                if (state != this.mIsMuted) {
                    changed = true;
                    this.mIsMuted = state;
                    AudioService.sendMsg(AudioService.this.mAudioHandler, 10, 2, 0, 0, this, 0);
                }
            }
            if (changed) {
                Intent intent = new Intent("android.media.STREAM_MUTE_CHANGED_ACTION");
                intent.putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", this.mStreamType);
                intent.putExtra("android.media.EXTRA_STREAM_VOLUME_MUTED", state);
                AudioService.this.sendBroadcastToAll(intent);
            }
        }

        public int getStreamType() {
            return this.mStreamType;
        }

        public void checkFixedVolumeDevices() {
            boolean isAvrcpAbsVolSupported = AudioService.this.mDeviceBroker.isAvrcpAbsoluteVolumeSupported();
            synchronized (VolumeStreamState.class) {
                if (AudioService.mStreamVolumeAlias[this.mStreamType] == 3) {
                    for (int i = 0; i < this.mIndexMap.size(); i++) {
                        int device = this.mIndexMap.keyAt(i);
                        int index = this.mIndexMap.valueAt(i);
                        if ((AudioService.this.mFullVolumeDevices & device) != 0 || ((AudioService.this.mFixedVolumeDevices & device) != 0 && index != 0)) {
                            this.mIndexMap.put(device, this.mIndexMax);
                        }
                        applyDeviceVolume_syncVSS(device, isAvrcpAbsVolSupported);
                    }
                }
            }
        }

        private int getValidIndex(int index) {
            int i = this.mIndexMin;
            if (index >= i) {
                if (AudioService.this.mUseFixedVolume || index > this.mIndexMax) {
                    return this.mIndexMax;
                }
                return index;
            }
            return i;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void dump(PrintWriter pw) {
            pw.print("   Muted: ");
            pw.println(this.mIsMuted);
            pw.print("   Min: ");
            pw.println((this.mIndexMin + 5) / 10);
            pw.print("   Max: ");
            pw.println((this.mIndexMax + 5) / 10);
            pw.print("   streamVolume:");
            pw.println(AudioService.this.getStreamVolume(this.mStreamType));
            pw.print("   Current: ");
            for (int i = 0; i < this.mIndexMap.size(); i++) {
                if (i > 0) {
                    pw.print(", ");
                }
                int device = this.mIndexMap.keyAt(i);
                pw.print(Integer.toHexString(device));
                String deviceName = device == 1073741824 ? "default" : AudioSystem.getOutputDeviceName(device);
                if (!deviceName.isEmpty()) {
                    pw.print(" (");
                    pw.print(deviceName);
                    pw.print(")");
                }
                pw.print(": ");
                int index = (this.mIndexMap.valueAt(i) + 5) / 10;
                pw.print(index);
            }
            pw.println();
            pw.print("   Devices: ");
            int devices = AudioService.this.getDevicesForStream(this.mStreamType);
            int i2 = 0;
            int n = 0;
            while (true) {
                int device2 = 1 << i2;
                if (device2 != 1073741824) {
                    if ((devices & device2) != 0) {
                        int n2 = n + 1;
                        if (n > 0) {
                            pw.print(", ");
                        }
                        pw.print(AudioSystem.getOutputDeviceName(device2));
                        n = n2;
                    }
                    i2++;
                } else {
                    return;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class AudioSystemThread extends Thread {
        AudioSystemThread() {
            super("AudioService");
        }

        @Override // java.lang.Thread, java.lang.Runnable
        public void run() {
            Looper.prepare();
            synchronized (AudioService.this) {
                AudioService.this.mAudioHandler = new AudioHandler();
                AudioService.this.notify();
            }
            Looper.loop();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class DeviceVolumeUpdate {
        private static final int NO_NEW_INDEX = -2049;
        final String mCaller;
        final int mDevice;
        final int mStreamType;
        private final int mVssVolIndex;

        DeviceVolumeUpdate(int streamType, int vssVolIndex, int device, String caller) {
            this.mStreamType = streamType;
            this.mVssVolIndex = vssVolIndex;
            this.mDevice = device;
            this.mCaller = caller;
        }

        DeviceVolumeUpdate(int streamType, int device, String caller) {
            this.mStreamType = streamType;
            this.mVssVolIndex = NO_NEW_INDEX;
            this.mDevice = device;
            this.mCaller = caller;
        }

        boolean hasVolumeIndex() {
            return this.mVssVolIndex != NO_NEW_INDEX;
        }

        int getVolumeIndex() throws IllegalStateException {
            Preconditions.checkState(this.mVssVolIndex != NO_NEW_INDEX);
            return this.mVssVolIndex;
        }
    }

    @VisibleForTesting
    public void postSetVolumeIndexOnDevice(int streamType, int vssVolIndex, int device, String caller) {
        sendMsg(this.mAudioHandler, MSG_SET_DEVICE_STREAM_VOLUME, 2, 0, 0, new DeviceVolumeUpdate(streamType, vssVolIndex, device, caller), 0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postApplyVolumeOnDevice(int streamType, int device, String caller) {
        sendMsg(this.mAudioHandler, MSG_SET_DEVICE_STREAM_VOLUME, 2, 0, 0, new DeviceVolumeUpdate(streamType, device, caller), 0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onSetVolumeIndexOnDevice(DeviceVolumeUpdate update) {
        VolumeStreamState streamState = this.mStreamStates[update.mStreamType];
        if (update.hasVolumeIndex()) {
            int index = update.getVolumeIndex();
            streamState.setIndex(index, update.mDevice, update.mCaller);
            AudioEventLogger audioEventLogger = sVolumeLogger;
            audioEventLogger.log(new AudioEventLogger.StringEvent(update.mCaller + " dev:0x" + Integer.toHexString(update.mDevice) + " volIdx:" + index));
        } else {
            AudioEventLogger audioEventLogger2 = sVolumeLogger;
            audioEventLogger2.log(new AudioEventLogger.StringEvent(update.mCaller + " update vol on dev:0x" + Integer.toHexString(update.mDevice)));
        }
        setDeviceVolume(streamState, update.mDevice);
    }

    void setDeviceVolume(VolumeStreamState streamState, int device) {
        boolean isAvrcpAbsVolSupported = this.mDeviceBroker.isAvrcpAbsoluteVolumeSupported();
        synchronized (VolumeStreamState.class) {
            streamState.applyDeviceVolume_syncVSS(device, isAvrcpAbsVolSupported);
            int numStreamTypes = AudioSystem.getNumStreamTypes();
            for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                if (streamType != streamState.mStreamType && mStreamVolumeAlias[streamType] == streamState.mStreamType) {
                    int streamDevice = getDeviceForStream(streamType);
                    if (device != streamDevice && isAvrcpAbsVolSupported && (device & 896) != 0) {
                        this.mStreamStates[streamType].applyDeviceVolume_syncVSS(device, isAvrcpAbsVolSupported);
                    }
                    this.mStreamStates[streamType].applyDeviceVolume_syncVSS(streamDevice, isAvrcpAbsVolSupported);
                }
            }
        }
        sendMsg(this.mAudioHandler, 1, 2, device, 0, streamState, 500);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class AudioHandler extends Handler {
        private AudioHandler() {
        }

        private void setAllVolumes(VolumeStreamState streamState) {
            streamState.applyAllVolumes();
            int numStreamTypes = AudioSystem.getNumStreamTypes();
            for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                if (streamType != streamState.mStreamType && AudioService.mStreamVolumeAlias[streamType] == streamState.mStreamType) {
                    AudioService.this.mStreamStates[streamType].applyAllVolumes();
                }
            }
        }

        private void persistVolume(VolumeStreamState streamState, int device) {
            if (!AudioService.this.mUseFixedVolume) {
                if ((!AudioService.this.mIsSingleVolume || streamState.mStreamType == 3) && streamState.hasValidSettingsName()) {
                    Settings.System.putIntForUser(AudioService.this.mContentResolver, streamState.getSettingNameForDevice(device), (streamState.getIndex(device) + 5) / 10, -2);
                }
            }
        }

        private void persistRingerMode(int ringerMode) {
            if (!AudioService.this.mUseFixedVolume) {
                Settings.Global.putInt(AudioService.this.mContentResolver, "mode_ringer", ringerMode);
            }
        }

        private String getSoundEffectFilePath(int effectType) {
            String THEME_PATH = SystemProperties.get(AudioService.PROPERTY_SOUND_THEME_PATH, AudioService.DEFAULT_SOUND_THEME_PATH);
            if (THEME_PATH != null) {
                String filePath = THEME_PATH + AudioService.SystemSoundPath + ((String) AudioService.SOUND_EFFECT_FILES.get(AudioService.this.SOUND_EFFECT_FILES_MAP[effectType][0]));
                if (new File(filePath).isFile()) {
                    Log.d(AudioService.TAG, "getSoundEffectFilePath THEME_PATH :" + filePath);
                    return filePath;
                }
            }
            String filePath2 = Environment.getProductDirectory() + AudioService.SOUND_EFFECTS_PATH + ((String) AudioService.SOUND_EFFECT_FILES.get(AudioService.this.SOUND_EFFECT_FILES_MAP[effectType][0]));
            if (!new File(filePath2).isFile()) {
                filePath2 = Environment.getRootDirectory() + AudioService.SOUND_EFFECTS_PATH + ((String) AudioService.SOUND_EFFECT_FILES.get(AudioService.this.SOUND_EFFECT_FILES_MAP[effectType][0]));
            }
            Log.d(AudioService.TAG, "getSoundEffectFilePath  :" + filePath2);
            return filePath2;
        }

        private boolean onLoadSoundEffects() {
            int status;
            synchronized (AudioService.this.mSoundEffectsLock) {
                if (AudioService.this.mSystemReady) {
                    if (AudioService.this.mSoundPool != null) {
                        return true;
                    }
                    AudioService.this.loadTouchSoundAssets();
                    AudioService.this.mSoundPool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder().setUsage(5).setContentType(4).build()).build();
                    AudioService.this.mSoundPoolCallBack = null;
                    AudioService.this.mSoundPoolListenerThread = new SoundPoolListenerThread();
                    AudioService.this.mSoundPoolListenerThread.start();
                    int attempts = 3;
                    while (AudioService.this.mSoundPoolCallBack == null) {
                        int attempts2 = attempts - 1;
                        if (attempts <= 0) {
                            break;
                        }
                        try {
                            AudioService.this.mSoundEffectsLock.wait(5000L);
                        } catch (InterruptedException e) {
                            Log.w(AudioService.TAG, "Interrupted while waiting sound pool listener thread.");
                        }
                        attempts = attempts2;
                    }
                    if (AudioService.this.mSoundPoolCallBack != null) {
                        int[] poolId = new int[AudioService.SOUND_EFFECT_FILES.size()];
                        for (int fileIdx = 0; fileIdx < AudioService.SOUND_EFFECT_FILES.size(); fileIdx++) {
                            poolId[fileIdx] = -1;
                        }
                        Log.d(AudioService.TAG, "onLoadSoundEffects SOUND_EFFECT_FILES.size():" + AudioService.SOUND_EFFECT_FILES.size());
                        int numSamples = 0;
                        for (int effect = 0; effect < 24; effect++) {
                            if (AudioService.this.SOUND_EFFECT_FILES_MAP[effect][1] != 0) {
                                if (poolId[AudioService.this.SOUND_EFFECT_FILES_MAP[effect][0]] != -1) {
                                    AudioService.this.SOUND_EFFECT_FILES_MAP[effect][1] = poolId[AudioService.this.SOUND_EFFECT_FILES_MAP[effect][0]];
                                } else {
                                    String filePath = getSoundEffectFilePath(effect);
                                    int sampleId = AudioService.this.mSoundPool.load(filePath, 0);
                                    if (sampleId > 0) {
                                        AudioService.this.SOUND_EFFECT_FILES_MAP[effect][1] = sampleId;
                                        poolId[AudioService.this.SOUND_EFFECT_FILES_MAP[effect][0]] = sampleId;
                                        numSamples++;
                                    } else {
                                        Log.w(AudioService.TAG, "Soundpool could not load file: " + filePath);
                                    }
                                }
                            }
                        }
                        if (numSamples > 0) {
                            AudioService.this.mSoundPoolCallBack.setSamples(poolId);
                            int attempts3 = 3;
                            status = 1;
                            while (status == 1) {
                                int attempts4 = attempts3 - 1;
                                if (attempts3 <= 0) {
                                    break;
                                }
                                try {
                                    AudioService.this.mSoundEffectsLock.wait(5000L);
                                    status = AudioService.this.mSoundPoolCallBack.status();
                                    attempts3 = attempts4;
                                } catch (InterruptedException e2) {
                                    Log.w(AudioService.TAG, "Interrupted while waiting sound pool callback.");
                                    attempts3 = attempts4;
                                }
                            }
                        } else {
                            status = -1;
                        }
                        if (AudioService.this.mSoundPoolLooper != null) {
                            AudioService.this.mSoundPoolLooper.quit();
                            AudioService.this.mSoundPoolLooper = null;
                        }
                        AudioService.this.mSoundPoolListenerThread = null;
                        if (status != 0) {
                            Log.w(AudioService.TAG, "onLoadSoundEffects(), Error " + status + " while loading samples");
                            for (int effect2 = 0; effect2 < 24; effect2++) {
                                if (AudioService.this.SOUND_EFFECT_FILES_MAP[effect2][1] > 0) {
                                    AudioService.this.SOUND_EFFECT_FILES_MAP[effect2][1] = -1;
                                }
                            }
                            AudioService.this.mSoundPool.release();
                            AudioService.this.mSoundPool = null;
                        }
                        return status == 0;
                    }
                    Log.w(AudioService.TAG, "onLoadSoundEffects() SoundPool listener or thread creation error");
                    if (AudioService.this.mSoundPoolLooper != null) {
                        AudioService.this.mSoundPoolLooper.quit();
                        AudioService.this.mSoundPoolLooper = null;
                    }
                    AudioService.this.mSoundPoolListenerThread = null;
                    AudioService.this.mSoundPool.release();
                    AudioService.this.mSoundPool = null;
                    return false;
                }
                Log.w(AudioService.TAG, "onLoadSoundEffects() called before boot complete");
                return false;
            }
        }

        private void onUnloadSoundEffects() {
            synchronized (AudioService.this.mSoundEffectsLock) {
                if (AudioService.this.mSoundPool == null) {
                    return;
                }
                int[] poolId = new int[AudioService.SOUND_EFFECT_FILES.size()];
                for (int fileIdx = 0; fileIdx < AudioService.SOUND_EFFECT_FILES.size(); fileIdx++) {
                    poolId[fileIdx] = 0;
                }
                for (int effect = 0; effect < 24; effect++) {
                    if (AudioService.this.SOUND_EFFECT_FILES_MAP[effect][1] > 0 && poolId[AudioService.this.SOUND_EFFECT_FILES_MAP[effect][0]] == 0) {
                        AudioService.this.mSoundPool.unload(AudioService.this.SOUND_EFFECT_FILES_MAP[effect][1]);
                        AudioService.this.SOUND_EFFECT_FILES_MAP[effect][1] = -1;
                        poolId[AudioService.this.SOUND_EFFECT_FILES_MAP[effect][0]] = -1;
                    }
                }
                AudioService.this.mSoundPool.release();
                AudioService.this.mSoundPool = null;
            }
        }

        private void onPlaySoundEffect(int effectType, int volume) {
            float volFloat;
            synchronized (AudioService.this.mSoundEffectsLock) {
                onLoadSoundEffects();
                if (AudioService.this.mSoundPool == null) {
                    return;
                }
                if (volume < 0) {
                    volFloat = (float) Math.pow(10.0d, AudioService.sSoundEffectVolumeDb / 20.0f);
                } else {
                    float volFloat2 = volume;
                    volFloat = volFloat2 / 1000.0f;
                }
                if (AudioService.this.SOUND_EFFECT_FILES_MAP[effectType][1] > 0) {
                    AudioService.this.mSoundPool.play(AudioService.this.SOUND_EFFECT_FILES_MAP[effectType][1], volFloat, volFloat, 0, 0, 1.0f);
                } else {
                    MediaPlayer mediaPlayer = new MediaPlayer();
                    try {
                        try {
                            String filePath = getSoundEffectFilePath(effectType);
                            mediaPlayer.setDataSource(filePath);
                            mediaPlayer.setAudioStreamType(1);
                            mediaPlayer.prepare();
                            mediaPlayer.setVolume(volFloat);
                            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { // from class: com.android.server.audio.AudioService.AudioHandler.1
                                @Override // android.media.MediaPlayer.OnCompletionListener
                                public void onCompletion(MediaPlayer mp) {
                                    AudioHandler.this.cleanupPlayer(mp);
                                }
                            });
                            mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() { // from class: com.android.server.audio.AudioService.AudioHandler.2
                                @Override // android.media.MediaPlayer.OnErrorListener
                                public boolean onError(MediaPlayer mp, int what, int extra) {
                                    AudioHandler.this.cleanupPlayer(mp);
                                    return true;
                                }
                            });
                            mediaPlayer.start();
                        } catch (IllegalArgumentException ex) {
                            Log.w(AudioService.TAG, "MediaPlayer IllegalArgumentException: " + ex);
                        } catch (IllegalStateException ex2) {
                            Log.w(AudioService.TAG, "MediaPlayer IllegalStateException: " + ex2);
                        }
                    } catch (IOException ex3) {
                        Log.w(AudioService.TAG, "MediaPlayer IOException: " + ex3);
                    }
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void cleanupPlayer(MediaPlayer mp) {
            if (mp != null) {
                try {
                    mp.stop();
                    mp.release();
                } catch (IllegalStateException ex) {
                    Log.w(AudioService.TAG, "MediaPlayer IllegalStateException: " + ex);
                }
            }
        }

        private void onPersistSafeVolumeState(int state) {
            Settings.Global.putInt(AudioService.this.mContentResolver, "audio_safe_volume_state", state);
        }

        private void onNotifyVolumeEvent(IAudioPolicyCallback apc, int direction) {
            try {
                Log.d(AudioService.TAG, "onNotifyVolumeEvent  direction:" + direction);
                apc.notifyVolumeAdjust(direction);
            } catch (Exception e) {
            }
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i == 0) {
                AudioService.this.setDeviceVolume((VolumeStreamState) msg.obj, msg.arg1);
                return;
            }
            if (i == 1) {
                persistVolume((VolumeStreamState) msg.obj, msg.arg1);
            } else if (i == 3) {
                persistRingerMode(AudioService.this.getRingerModeInternal());
            } else if (i == 4) {
                AudioService.this.onAudioServerDied();
            } else if (i == 5) {
                onPlaySoundEffect(msg.arg1, msg.arg2);
            } else {
                if (i == 7) {
                    boolean loaded = onLoadSoundEffects();
                    if (msg.obj != null) {
                        LoadSoundEffectReply reply = (LoadSoundEffectReply) msg.obj;
                        synchronized (reply) {
                            reply.mStatus = loaded ? 0 : -1;
                            reply.notify();
                        }
                    }
                } else if (i == 8) {
                    String eventSource = (String) msg.obj;
                    int useCase = msg.arg1;
                    int config = msg.arg2;
                    if (useCase == 1) {
                        Log.wtf(AudioService.TAG, "Invalid force use FOR_MEDIA in AudioService from " + eventSource);
                        return;
                    }
                    AudioService.sForceUseLogger.log(new AudioServiceEvents.ForceUseEvent(useCase, config, eventSource));
                    AudioSystem.setForceUse(useCase, config);
                } else if (i == 100) {
                    AudioService.this.mPlaybackMonitor.disableAudioForUid(msg.arg1 == 1, msg.arg2);
                    AudioService.this.mAudioEventWakeLock.release();
                } else {
                    switch (i) {
                        case 10:
                            setAllVolumes((VolumeStreamState) msg.obj);
                            return;
                        case 11:
                            AudioService.this.onCheckMusicActive((String) msg.obj);
                            return;
                        case 12:
                        case 13:
                            AudioService.this.onConfigureSafeVolume(msg.what == 13, (String) msg.obj);
                            return;
                        case 14:
                            onPersistSafeVolumeState(msg.arg1);
                            return;
                        case 15:
                            onUnloadSoundEffects();
                            return;
                        case 16:
                            AudioService.this.onSystemReady();
                            return;
                        case 17:
                            int musicActiveMs = msg.arg1;
                            Settings.Secure.putIntForUser(AudioService.this.mContentResolver, "unsafe_volume_music_active_ms", musicActiveMs, -2);
                            return;
                        case 18:
                            AudioService.this.onUnmuteStream(msg.arg1, msg.arg2);
                            return;
                        case 19:
                            AudioService.this.onDynPolicyMixStateUpdate((String) msg.obj, msg.arg1);
                            return;
                        case 20:
                            AudioService.this.onIndicateSystemReady();
                            return;
                        case 21:
                            AudioService.this.onAccessoryPlugMediaUnmute(msg.arg1);
                            return;
                        case 22:
                            onNotifyVolumeEvent((IAudioPolicyCallback) msg.obj, msg.arg1);
                            return;
                        case 23:
                            AudioService.this.onDispatchAudioServerStateChange(msg.arg1 == 1);
                            return;
                        case 24:
                            AudioService.this.onEnableSurroundFormats((ArrayList) msg.obj);
                            return;
                        case 25:
                            AudioService.this.onUpdateRingerModeServiceInt();
                            return;
                        case AudioService.MSG_SET_DEVICE_STREAM_VOLUME /* 26 */:
                            AudioService.this.onSetVolumeIndexOnDevice((DeviceVolumeUpdate) msg.obj);
                            return;
                        case AudioService.MSG_OBSERVE_DEVICES_FOR_ALL_STREAMS /* 27 */:
                            AudioService.this.onObserveDevicesForAllStreams();
                            return;
                        case AudioService.MSG_HDMI_VOLUME_CHECK /* 28 */:
                            AudioService.this.onCheckVolumeCecOnHdmiConnection(msg.arg1, (String) msg.obj);
                            return;
                        case 29:
                            AudioService.this.onPlaybackConfigChange((List) msg.obj);
                            return;
                        default:
                            return;
                    }
                }
            }
        }
    }

    /* loaded from: classes.dex */
    private class SettingsObserver extends ContentObserver {
        SettingsObserver() {
            super(new Handler());
            AudioService.this.mContentResolver.registerContentObserver(Settings.Global.getUriFor("zen_mode"), false, this);
            AudioService.this.mContentResolver.registerContentObserver(Settings.Global.getUriFor("zen_mode_config_etag"), false, this);
            AudioService.this.mContentResolver.registerContentObserver(Settings.System.getUriFor("mode_ringer_streams_affected"), false, this);
            AudioService.this.mContentResolver.registerContentObserver(Settings.Global.getUriFor("dock_audio_media_enabled"), false, this);
            AudioService.this.mContentResolver.registerContentObserver(Settings.System.getUriFor("master_mono"), false, this);
            AudioService.this.mContentResolver.registerContentObserver(Settings.System.getUriFor("master_balance"), false, this);
            AudioService.this.mEncodedSurroundMode = Settings.Global.getInt(AudioService.this.mContentResolver, "encoded_surround_output", 0);
            AudioService.this.mContentResolver.registerContentObserver(Settings.Global.getUriFor("encoded_surround_output"), false, this);
            AudioService.this.mEnabledSurroundFormats = Settings.Global.getString(AudioService.this.mContentResolver, "encoded_surround_output_enabled_formats");
            AudioService.this.mContentResolver.registerContentObserver(Settings.Global.getUriFor("encoded_surround_output_enabled_formats"), false, this);
            AudioService.this.mContentResolver.registerContentObserver(Settings.Secure.getUriFor("voice_interaction_service"), false, this);
            AudioService.this.mContentResolver.registerContentObserver(Settings.Secure.getUriFor("rtt_calling_mode"), false, this);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            synchronized (AudioService.this.mSettingsLock) {
                if (AudioService.this.updateRingerAndZenModeAffectedStreams()) {
                    AudioService.this.setRingerModeInt(AudioService.this.getRingerModeInternal(), false);
                }
                AudioService.this.readDockAudioSettings(AudioService.this.mContentResolver);
                AudioService.this.updateMasterMono(AudioService.this.mContentResolver);
                AudioService.this.updateMasterBalance(AudioService.this.mContentResolver);
                updateEncodedSurroundOutput();
                AudioService.this.sendEnabledSurroundFormats(AudioService.this.mContentResolver, AudioService.this.mSurroundModeChanged);
                AudioService.this.updateAssistantUId(false);
                AudioService.this.updateRttEanbled(AudioService.this.mContentResolver);
            }
        }

        private void updateEncodedSurroundOutput() {
            int newSurroundMode = Settings.Global.getInt(AudioService.this.mContentResolver, "encoded_surround_output", 0);
            if (AudioService.this.mEncodedSurroundMode != newSurroundMode) {
                AudioService.this.sendEncodedSurroundMode(newSurroundMode, "SettingsObserver");
                AudioService.this.mDeviceBroker.toggleHdmiIfConnected_Async();
                AudioService.this.mEncodedSurroundMode = newSurroundMode;
                AudioService.this.mSurroundModeChanged = true;
                return;
            }
            AudioService.this.mSurroundModeChanged = false;
        }
    }

    public void avrcpSupportsAbsoluteVolume(String address, boolean support) {
        AudioEventLogger audioEventLogger = sVolumeLogger;
        audioEventLogger.log(new AudioEventLogger.StringEvent("avrcpSupportsAbsoluteVolume addr=" + address + " support=" + support));
        this.mDeviceBroker.setAvrcpAbsoluteVolumeSupported(support);
        sendMsg(this.mAudioHandler, 0, 2, 128, 0, this.mStreamStates[3], 0);
    }

    @VisibleForTesting
    public boolean hasMediaDynamicPolicy() {
        synchronized (this.mAudioPolicies) {
            if (this.mAudioPolicies.isEmpty()) {
                return false;
            }
            Collection<AudioPolicyProxy> appColl = this.mAudioPolicies.values();
            for (AudioPolicyProxy app : appColl) {
                if (app.hasMixAffectingUsage(1, 3)) {
                    return true;
                }
            }
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void checkMusicActive(int deviceType, String caller) {
        if ((67108876 & deviceType) != 0) {
            sendMsg(this.mAudioHandler, 11, 0, 0, 0, caller, MUSIC_ACTIVE_POLL_PERIOD_MS);
        }
    }

    /* loaded from: classes.dex */
    private class AudioServiceBroadcastReceiver extends BroadcastReceiver {
        private AudioServiceBroadcastReceiver() {
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            int config;
            String action = intent.getAction();
            if (action.equals("android.intent.action.DOCK_EVENT")) {
                int dockState = intent.getIntExtra("android.intent.extra.DOCK_STATE", 0);
                if (dockState == 1) {
                    config = 7;
                } else if (dockState == 2) {
                    config = 6;
                } else if (dockState == 3) {
                    config = 8;
                } else if (dockState == 4) {
                    config = 9;
                } else {
                    config = 0;
                }
                if (dockState != 3 && (dockState != 0 || AudioService.this.mDockState != 3)) {
                    AudioService.this.mDeviceBroker.setForceUse_Async(3, config, "ACTION_DOCK_EVENT intent");
                }
                AudioService.this.mDockState = dockState;
            } else if (action.equals("android.bluetooth.headset.profile.action.ACTIVE_DEVICE_CHANGED") || action.equals("android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED")) {
                AudioService.this.mDeviceBroker.receiveBtEvent(intent);
            } else if (action.equals("android.intent.action.SCREEN_ON")) {
                if (AudioService.this.mMonitorRotation) {
                    RotationHelper.enable();
                }
                AudioSystem.setParameters("screen_state=on");
            } else if (action.equals("android.intent.action.SCREEN_OFF")) {
                if (AudioService.this.mMonitorRotation) {
                    RotationHelper.disable();
                }
                AudioSystem.setParameters("screen_state=off");
            } else if (action.equals("android.intent.action.CONFIGURATION_CHANGED")) {
                AudioService.this.handleConfigurationChanged(context);
            } else if (action.equals("android.intent.action.USER_SWITCHED")) {
                if (AudioService.this.mUserSwitchedReceived) {
                    AudioService.this.mDeviceBroker.postBroadcastBecomingNoisy();
                }
                AudioService.this.mUserSwitchedReceived = true;
                AudioService.this.mMediaFocusControl.discardAudioFocusOwner();
                AudioService.this.readAudioSettings(true);
                AudioService.sendMsg(AudioService.this.mAudioHandler, 10, 2, 0, 0, AudioService.this.mStreamStates[3], 0);
            } else if (action.equals("android.intent.action.USER_BACKGROUND")) {
                int userId = intent.getIntExtra("android.intent.extra.user_handle", -1);
                if (userId >= 0) {
                    UserInfo userInfo = UserManagerService.getInstance().getUserInfo(userId);
                    AudioService.this.killBackgroundUserProcessesWithRecordAudioPermission(userInfo);
                }
                UserManagerService.getInstance().setUserRestriction("no_record_audio", true, userId);
            } else if (action.equals("android.intent.action.USER_FOREGROUND")) {
                UserManagerService.getInstance().setUserRestriction("no_record_audio", false, intent.getIntExtra("android.intent.extra.user_handle", -1));
            } else if (action.equals("android.bluetooth.adapter.action.STATE_CHANGED")) {
                int state = intent.getIntExtra("android.bluetooth.adapter.extra.STATE", -1);
                if (state == 10 || state == 13) {
                    AudioService.this.mDeviceBroker.disconnectAllBluetoothProfiles();
                }
            } else if (action.equals("android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION") || action.equals("android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION")) {
                AudioService.this.handleAudioEffectBroadcast(context, intent);
            } else if (action.equals("android.intent.action.PACKAGES_SUSPENDED")) {
                int[] suspendedUids = intent.getIntArrayExtra("android.intent.extra.changed_uid_list");
                String[] suspendedPackages = intent.getStringArrayExtra("android.intent.extra.changed_package_list");
                if (suspendedPackages == null || suspendedUids == null || suspendedPackages.length != suspendedUids.length) {
                    return;
                }
                for (int i = 0; i < suspendedUids.length; i++) {
                    if (!TextUtils.isEmpty(suspendedPackages[i])) {
                        AudioService.this.mMediaFocusControl.noFocusForSuspendedApp(suspendedPackages[i], suspendedUids[i]);
                    }
                }
            }
        }
    }

    /* loaded from: classes.dex */
    private class AudioServiceUserRestrictionsListener implements UserManagerInternal.UserRestrictionsListener {
        private AudioServiceUserRestrictionsListener() {
        }

        public void onUserRestrictionsChanged(int userId, Bundle newRestrictions, Bundle prevRestrictions) {
            boolean wasRestricted = prevRestrictions.getBoolean("no_unmute_microphone");
            boolean isRestricted = newRestrictions.getBoolean("no_unmute_microphone");
            if (wasRestricted != isRestricted) {
                AudioService.this.setMicrophoneMuteNoCallerCheck(isRestricted, userId);
            }
            boolean z = true;
            boolean wasRestricted2 = prevRestrictions.getBoolean("no_adjust_volume") || prevRestrictions.getBoolean("disallow_unmute_device");
            if (!newRestrictions.getBoolean("no_adjust_volume") && !newRestrictions.getBoolean("disallow_unmute_device")) {
                z = false;
            }
            boolean isRestricted2 = z;
            if (wasRestricted2 != isRestricted2) {
                AudioService.this.setMasterMuteInternalNoCallerCheck(isRestricted2, 0, userId);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleAudioEffectBroadcast(Context context, Intent intent) {
        ResolveInfo ri;
        String target = intent.getPackage();
        if (target != null) {
            Log.w(TAG, "effect broadcast already targeted to " + target);
            return;
        }
        intent.addFlags(32);
        List<ResolveInfo> ril = context.getPackageManager().queryBroadcastReceivers(intent, 0);
        if (ril == null || ril.size() == 0 || (ri = ril.get(0)) == null || ri.activityInfo == null || ri.activityInfo.packageName == null) {
            Log.w(TAG, "couldn't find receiver package for effect intent");
            return;
        }
        intent.setPackage(ri.activityInfo.packageName);
        context.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void killBackgroundUserProcessesWithRecordAudioPermission(UserInfo oldUser) {
        PackageManager pm = this.mContext.getPackageManager();
        ComponentName homeActivityName = null;
        if (!oldUser.isManagedProfile()) {
            homeActivityName = ((ActivityTaskManagerInternal) LocalServices.getService(ActivityTaskManagerInternal.class)).getHomeActivityForUser(oldUser.id);
        }
        String[] permissions = {"android.permission.RECORD_AUDIO"};
        try {
            List<PackageInfo> packages = AppGlobals.getPackageManager().getPackagesHoldingPermissions(permissions, 0, oldUser.id).getList();
            for (int j = packages.size() - 1; j >= 0; j--) {
                PackageInfo pkg = packages.get(j);
                if (UserHandle.getAppId(pkg.applicationInfo.uid) >= 10000 && pm.checkPermission("android.permission.INTERACT_ACROSS_USERS", pkg.packageName) != 0 && (homeActivityName == null || !pkg.packageName.equals(homeActivityName.getPackageName()) || !pkg.applicationInfo.isSystemApp())) {
                    try {
                        int uid = pkg.applicationInfo.uid;
                        ActivityManager.getService().killUid(UserHandle.getAppId(uid), UserHandle.getUserId(uid), "killBackgroundUserProcessesWithAudioRecordPermission");
                    } catch (RemoteException e) {
                        Log.w(TAG, "Error calling killUid", e);
                    }
                }
            }
        } catch (RemoteException e2) {
            throw new AndroidRuntimeException(e2);
        }
    }

    private boolean forceFocusDuckingForAccessibility(AudioAttributes aa, int request, int uid) {
        Bundle extraInfo;
        if (aa != null && aa.getUsage() == 11 && request == 3 && (extraInfo = aa.getBundle()) != null && extraInfo.getBoolean("a11y_force_ducking")) {
            if (uid == 0) {
                return true;
            }
            synchronized (this.mAccessibilityServiceUidsLock) {
                if (this.mAccessibilityServiceUids != null) {
                    int callingUid = Binder.getCallingUid();
                    for (int i = 0; i < this.mAccessibilityServiceUids.length; i++) {
                        if (this.mAccessibilityServiceUids[i] == callingUid) {
                            return true;
                        }
                    }
                }
                return false;
            }
        }
        return false;
    }

    public int requestAudioFocus(AudioAttributes aa, int durationHint, IBinder cb, IAudioFocusDispatcher fd, String clientId, String callingPackageName, int flags, IAudioPolicyCallback pcb, int sdk) {
        if ((flags & 4) == 4) {
            if ("AudioFocus_For_Phone_Ring_And_Calls".equals(clientId)) {
                if (this.mContext.checkCallingOrSelfPermission("android.permission.MODIFY_PHONE_STATE") != 0) {
                    Log.e(TAG, "Invalid permission to (un)lock audio focus", new Exception());
                    return 0;
                }
            } else {
                synchronized (this.mAudioPolicies) {
                    if (!this.mAudioPolicies.containsKey(pcb.asBinder())) {
                        Log.e(TAG, "Invalid unregistered AudioPolicy to (un)lock audio focus");
                        return 0;
                    }
                }
            }
        }
        if (callingPackageName != null && clientId != null) {
            if (aa != null) {
                return this.mMediaFocusControl.requestAudioFocus(aa, durationHint, cb, fd, clientId, callingPackageName, flags, sdk, forceFocusDuckingForAccessibility(aa, durationHint, Binder.getCallingUid()));
            }
        }
        Log.e(TAG, "Invalid null parameter to request audio focus");
        return 0;
    }

    public int requestAudioFocusPosition(AudioAttributes aa, int durationHint, IBinder cb, IAudioFocusDispatcher fd, String clientId, int position, int flags, IAudioPolicyCallback pcb, int sdk) {
        if ((flags & 4) == 4) {
            if ("AudioFocus_For_Phone_Ring_And_Calls".equals(clientId)) {
                if (this.mContext.checkCallingOrSelfPermission("android.permission.MODIFY_PHONE_STATE") != 0) {
                    Log.e(TAG, "Invalid permission to (un)lock audio focus", new Exception());
                    return 0;
                }
            } else {
                synchronized (this.mAudioPolicies) {
                    if (!this.mAudioPolicies.containsKey(pcb.asBinder())) {
                        Log.e(TAG, "Invalid unregistered AudioPolicy to (un)lock audio focus");
                        return 0;
                    }
                }
            }
        }
        if (position >= 0 && position <= 1 && clientId != null) {
            if (aa != null) {
                return this.mMediaFocusControl.requestAudioFocus(aa, durationHint, cb, fd, clientId, position, flags, sdk, forceFocusDuckingForAccessibility(aa, durationHint, Binder.getCallingUid()));
            }
        }
        Log.e(TAG, "Invalid null parameter to request audio focus");
        return 0;
    }

    public void changeAudioFocusPosition(String clientId, int position) {
        this.mMediaFocusControl.changeAudioFocusPosition(clientId, position);
    }

    public int abandonAudioFocus(IAudioFocusDispatcher fd, String clientId, AudioAttributes aa, String callingPackageName) {
        return this.mMediaFocusControl.abandonAudioFocus(fd, clientId, aa, callingPackageName);
    }

    public void unregisterAudioFocusClient(String clientId) {
        this.mMediaFocusControl.unregisterAudioFocusClient(clientId);
    }

    public int getCurrentAudioFocus() {
        return this.mMediaFocusControl.getCurrentAudioFocus();
    }

    public AudioAttributes getCurrentAudioFocusAttributes() {
        return this.mMediaFocusControl.getCurrentAudioFocusAttributes();
    }

    public int getFocusRampTimeMs(int focusGain, AudioAttributes attr) {
        MediaFocusControl mediaFocusControl = this.mMediaFocusControl;
        return MediaFocusControl.getFocusRampTimeMs(focusGain, attr);
    }

    @VisibleForTesting
    public boolean hasAudioFocusUsers() {
        return this.mMediaFocusControl.hasAudioFocusUsers();
    }

    public String getCurrentAudioFocusPackageName() {
        return this.mMediaFocusControl.getCurrentAudioFocusPackageName();
    }

    public List<String> getAudioFocusPackageNameList() {
        return this.mMediaFocusControl.getAudioFocusPackageNameList();
    }

    public List<String> getAudioFocusPackageNameListByPosition(int position) {
        return this.mMediaFocusControl.getAudioFocusPackageNameListByPosition(position);
    }

    public List<xpAudioSessionInfo> getActiveSessionList() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.getActiveSessionList();
            }
            return null;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.getActiveSessionList();
        }
        return null;
    }

    public String getLastAudioFocusPackageName() {
        return this.mMediaFocusControl.getLastAudioFocusPackageName();
    }

    public void startAudioCapture(int audioSession, int usage) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.startAudioCapture(audioSession, usage);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.startAudioCapture(audioSession, usage);
        }
    }

    public void stopAudioCapture(int audioSession, int usage) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.stopAudioCapture(audioSession, usage);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.stopAudioCapture(audioSession, usage);
        }
    }

    public void startSpeechEffect(int audioSession) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.startSpeechEffect(audioSession);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.startSpeechEffect(audioSession);
        }
    }

    public void stopSpeechEffect(int audioSession) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.stopSpeechEffect(audioSession);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.stopSpeechEffect(audioSession);
        }
    }

    public boolean checkStreamActive(int streamType) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.checkStreamActive(streamType);
            }
            return false;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.checkStreamActive(streamType);
        }
        return false;
    }

    public boolean isAnyStreamActive() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.isAnyStreamActive();
            }
            return false;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.isAnyStreamActive();
        }
        return false;
    }

    public int checkStreamCanPlay(int streamType) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.checkStreamCanPlay(streamType);
            }
            return 2;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.checkStreamCanPlay(streamType);
        }
        return 2;
    }

    public void setMusicLimitMode(boolean mode) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setMusicLimitMode(mode);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.setMusicLimitMode(mode);
        }
    }

    public boolean isMusicLimitMode() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.isMusicLimitMode();
            }
            return false;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.isMusicLimitMode();
        }
        return false;
    }

    public void setSessionIdStatus(int sessionId, int position, int status) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.setSessionIdStatus(sessionId, position, status);
        }
    }

    private boolean readCameraSoundForced() {
        return SystemProperties.getBoolean("audio.camerasound.force", false) || this.mContext.getResources().getBoolean(17891386);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleConfigurationChanged(Context context) {
        try {
            Configuration config = context.getResources().getConfiguration();
            sendMsg(this.mAudioHandler, 12, 0, 0, 0, TAG, 0);
            boolean cameraSoundForced = readCameraSoundForced();
            synchronized (this.mSettingsLock) {
                boolean cameraSoundForcedChanged = cameraSoundForced != this.mCameraSoundForced;
                this.mCameraSoundForced = cameraSoundForced;
                if (cameraSoundForcedChanged) {
                    if (!this.mIsSingleVolume) {
                        synchronized (VolumeStreamState.class) {
                            VolumeStreamState s = this.mStreamStates[7];
                            if (cameraSoundForced) {
                                s.setAllIndexesToMax();
                                this.mRingerModeAffectedStreams &= -129;
                            } else {
                                s.setAllIndexes(this.mStreamStates[1], TAG);
                                this.mRingerModeAffectedStreams |= 128;
                            }
                        }
                        setRingerModeInt(getRingerModeInternal(), false);
                    }
                    this.mDeviceBroker.setForceUse_Async(4, cameraSoundForced ? 11 : 0, "handleConfigurationChanged");
                    sendMsg(this.mAudioHandler, 10, 2, 0, 0, this.mStreamStates[7], 0);
                }
            }
            this.mVolumeController.setLayoutDirection(config.getLayoutDirection());
        } catch (Exception e) {
            Log.e(TAG, "Error handling configuration change: ", e);
        }
    }

    public void setRingtonePlayer(IRingtonePlayer player) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.REMOTE_AUDIO_PLAYBACK", null);
        this.mRingtonePlayer = player;
    }

    public IRingtonePlayer getRingtonePlayer() {
        return this.mRingtonePlayer;
    }

    public AudioRoutesInfo startWatchingRoutes(IAudioRoutesObserver observer) {
        return this.mDeviceBroker.startWatchingRoutes(observer);
    }

    private int safeMediaVolumeIndex(int device) {
        if ((67108876 & device) == 0) {
            return MAX_STREAM_VOLUME[3];
        }
        if (device == 67108864) {
            return this.mSafeUsbMediaVolumeIndex;
        }
        return this.mSafeMediaVolumeIndex;
    }

    private void setSafeMediaVolumeEnabled(boolean on, String caller) {
        synchronized (this.mSafeMediaVolumeStateLock) {
            if (this.mSafeMediaVolumeState != 0 && this.mSafeMediaVolumeState != 1) {
                if (on && this.mSafeMediaVolumeState == 2) {
                    this.mSafeMediaVolumeState = 3;
                    enforceSafeMediaVolume(caller);
                } else if (!on && this.mSafeMediaVolumeState == 3) {
                    this.mSafeMediaVolumeState = 2;
                    this.mMusicActiveMs = 1;
                    saveMusicActiveMs();
                    sendMsg(this.mAudioHandler, 11, 0, 0, 0, caller, MUSIC_ACTIVE_POLL_PERIOD_MS);
                }
            }
        }
    }

    private void enforceSafeMediaVolume(String caller) {
        VolumeStreamState streamState = this.mStreamStates[3];
        int devices = 67108876;
        int i = 0;
        while (devices != 0) {
            int i2 = i + 1;
            int device = 1 << i;
            if ((device & devices) == 0) {
                i = i2;
            } else {
                int index = streamState.getIndex(device);
                if (index > safeMediaVolumeIndex(device)) {
                    streamState.setIndex(safeMediaVolumeIndex(device), device, caller);
                    sendMsg(this.mAudioHandler, 0, 2, device, 0, streamState, 0);
                }
                devices &= ~device;
                i = i2;
            }
        }
    }

    private boolean checkSafeMediaVolume(int streamType, int index, int device) {
        synchronized (this.mSafeMediaVolumeStateLock) {
            if (this.mSafeMediaVolumeState == 3 && mStreamVolumeAlias[streamType] == 3 && (67108876 & device) != 0 && index > safeMediaVolumeIndex(device)) {
                return false;
            }
            return true;
        }
    }

    public void disableSafeMediaVolume(String callingPackage) {
        enforceVolumeController("disable the safe media volume");
        synchronized (this.mSafeMediaVolumeStateLock) {
            setSafeMediaVolumeEnabled(false, callingPackage);
            if (this.mPendingVolumeCommand != null) {
                onSetStreamVolume(this.mPendingVolumeCommand.mStreamType, this.mPendingVolumeCommand.mIndex, this.mPendingVolumeCommand.mFlags, this.mPendingVolumeCommand.mDevice, callingPackage);
                this.mPendingVolumeCommand = null;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class MyDisplayStatusCallback implements HdmiPlaybackClient.DisplayStatusCallback {
        private MyDisplayStatusCallback() {
        }

        public void onComplete(int status) {
            synchronized (AudioService.this.mHdmiClientLock) {
                if (AudioService.this.mHdmiManager != null) {
                    AudioService.this.mHdmiCecSink = status != -1;
                    if (AudioService.this.mHdmiCecSink) {
                        Log.d(AudioService.TAG, "CEC sink: setting HDMI as full vol device");
                        AudioService.this.mFullVolumeDevices |= 1024;
                    } else {
                        Log.d(AudioService.TAG, "TV, no CEC: setting HDMI as regular vol device");
                        AudioService.this.mFullVolumeDevices &= -1025;
                    }
                    AudioService.this.checkAddAllFixedVolumeDevices(1024, "HdmiPlaybackClient.DisplayStatusCallback");
                }
            }
        }
    }

    public int setHdmiSystemAudioSupported(boolean on) {
        int device = 0;
        synchronized (this.mHdmiClientLock) {
            if (this.mHdmiManager != null) {
                if (this.mHdmiTvClient == null && this.mHdmiAudioSystemClient == null) {
                    Log.w(TAG, "Only Hdmi-Cec enabled TV or audio system device supportssystem audio mode.");
                    return 0;
                }
                if (this.mHdmiSystemAudioSupported != on) {
                    this.mHdmiSystemAudioSupported = on;
                    int config = on ? 12 : 0;
                    this.mDeviceBroker.setForceUse_Async(5, config, "setHdmiSystemAudioSupported");
                }
                device = getDevicesForStream(3);
            }
            return device;
        }
    }

    public boolean isHdmiSystemAudioSupported() {
        return this.mHdmiSystemAudioSupported;
    }

    private void initA11yMonitoring() {
        AccessibilityManager accessibilityManager = (AccessibilityManager) this.mContext.getSystemService("accessibility");
        updateDefaultStreamOverrideDelay(accessibilityManager.isTouchExplorationEnabled());
        updateA11yVolumeAlias(accessibilityManager.isAccessibilityVolumeStreamActive());
        accessibilityManager.addTouchExplorationStateChangeListener(this, null);
        accessibilityManager.addAccessibilityServicesStateChangeListener(this, null);
    }

    @Override // android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener
    public void onTouchExplorationStateChanged(boolean enabled) {
        updateDefaultStreamOverrideDelay(enabled);
    }

    private void updateDefaultStreamOverrideDelay(boolean touchExploreEnabled) {
        if (touchExploreEnabled) {
            sStreamOverrideDelayMs = 1000;
        } else {
            sStreamOverrideDelayMs = 0;
        }
        Log.d(TAG, "Touch exploration enabled=" + touchExploreEnabled + " stream override delay is now " + sStreamOverrideDelayMs + " ms");
    }

    public void onAccessibilityServicesStateChanged(AccessibilityManager accessibilityManager) {
        updateA11yVolumeAlias(accessibilityManager.isAccessibilityVolumeStreamActive());
    }

    private void updateA11yVolumeAlias(boolean a11VolEnabled) {
        Log.d(TAG, "Accessibility volume enabled = " + a11VolEnabled);
        if (sIndependentA11yVolume != a11VolEnabled) {
            sIndependentA11yVolume = a11VolEnabled;
            int i = 1;
            updateStreamVolumeAlias(true, TAG);
            VolumeController volumeController = this.mVolumeController;
            if (!sIndependentA11yVolume) {
                i = 0;
            }
            volumeController.setA11yMode(i);
            this.mVolumeController.postVolumeChanged(10, 0);
        }
    }

    public boolean isCameraSoundForced() {
        boolean z;
        synchronized (this.mSettingsLock) {
            z = this.mCameraSoundForced;
        }
        return z;
    }

    private void dumpRingerMode(PrintWriter pw) {
        pw.println("\nRinger mode: ");
        pw.println("- mode (internal) = " + RINGER_MODE_NAMES[this.mRingerMode]);
        pw.println("- mode (external) = " + RINGER_MODE_NAMES[this.mRingerModeExternal]);
        dumpRingerModeStreams(pw, "affected", this.mRingerModeAffectedStreams);
        dumpRingerModeStreams(pw, "muted", this.mRingerAndZenModeMutedStreams);
        pw.print("- delegate = ");
        pw.println(this.mRingerModeDelegate);
    }

    private void dumpRingerModeStreams(PrintWriter pw, String type, int streams) {
        pw.print("- ringer mode ");
        pw.print(type);
        pw.print(" streams = 0x");
        pw.print(Integer.toHexString(streams));
        if (streams != 0) {
            pw.print(" (");
            boolean first = true;
            for (int i = 0; i < AudioSystem.STREAM_NAMES.length; i++) {
                int stream = 1 << i;
                if ((streams & stream) != 0) {
                    if (!first) {
                        pw.print(',');
                    }
                    pw.print(AudioSystem.STREAM_NAMES[i]);
                    streams &= ~stream;
                    first = false;
                }
            }
            if (streams != 0) {
                if (!first) {
                    pw.print(',');
                }
                pw.print(streams);
            }
            pw.print(')');
        }
        pw.println();
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            this.mMediaFocusControl.dump(pw);
            dumpStreamStates(pw);
            dumpRingerMode(pw);
            pw.println("\nAudio routes:");
            pw.print("  mMainType=0x");
            pw.println(Integer.toHexString(this.mDeviceBroker.getCurAudioRoutes().mainType));
            pw.print("  mBluetoothName=");
            pw.println(this.mDeviceBroker.getCurAudioRoutes().bluetoothName);
            pw.println("\nOther state:");
            pw.print("  mVolumeController=");
            pw.println(this.mVolumeController);
            pw.print("  mSafeMediaVolumeState=");
            pw.println(safeMediaVolumeStateToString(this.mSafeMediaVolumeState));
            pw.print("  mSafeMediaVolumeIndex=");
            pw.println(this.mSafeMediaVolumeIndex);
            pw.print("  mSafeUsbMediaVolumeIndex=");
            pw.println(this.mSafeUsbMediaVolumeIndex);
            pw.print("  mSafeUsbMediaVolumeDbfs=");
            pw.println(this.mSafeUsbMediaVolumeDbfs);
            pw.print("  sIndependentA11yVolume=");
            pw.println(sIndependentA11yVolume);
            pw.print("  mPendingVolumeCommand=");
            pw.println(this.mPendingVolumeCommand);
            pw.print("  mMusicActiveMs=");
            pw.println(this.mMusicActiveMs);
            pw.print("  mMcc=");
            pw.println(this.mMcc);
            pw.print("  mCameraSoundForced=");
            pw.println(this.mCameraSoundForced);
            pw.print("  mHasVibrator=");
            pw.println(this.mHasVibrator);
            pw.print("  mVolumePolicy=");
            pw.println(this.mVolumePolicy);
            pw.print("  mAvrcpAbsVolSupported=");
            pw.println(this.mDeviceBroker.isAvrcpAbsoluteVolumeSupported());
            pw.print("  mIsSingleVolume=");
            pw.println(this.mIsSingleVolume);
            pw.print("  mUseFixedVolume=");
            pw.println(this.mUseFixedVolume);
            pw.print("  mFixedVolumeDevices=0x");
            pw.println(Integer.toHexString(this.mFixedVolumeDevices));
            pw.print("  mHdmiCecSink=");
            pw.println(this.mHdmiCecSink);
            pw.print("  mHdmiAudioSystemClient=");
            pw.println(this.mHdmiAudioSystemClient);
            pw.print("  mHdmiPlaybackClient=");
            pw.println(this.mHdmiPlaybackClient);
            pw.print("  mHdmiTvClient=");
            pw.println(this.mHdmiTvClient);
            pw.print("  mHdmiSystemAudioSupported=");
            pw.println(this.mHdmiSystemAudioSupported);
            dumpAudioPolicies(pw);
            this.mDynPolicyLogger.dump(pw);
            this.mPlaybackMonitor.dump(pw);
            this.mRecordMonitor.dump(pw);
            pw.println("\n");
            pw.println("\nEvent logs:");
            this.mModeLogger.dump(pw);
            pw.println("\n");
            sDeviceLogger.dump(pw);
            pw.println("\n");
            sForceUseLogger.dump(pw);
            pw.println("\n");
            sVolumeLogger.dump(pw);
        }
    }

    private static String safeMediaVolumeStateToString(int state) {
        if (state != 0) {
            if (state != 1) {
                if (state != 2) {
                    if (state == 3) {
                        return "SAFE_MEDIA_VOLUME_ACTIVE";
                    }
                    return null;
                }
                return "SAFE_MEDIA_VOLUME_INACTIVE";
            }
            return "SAFE_MEDIA_VOLUME_DISABLED";
        }
        return "SAFE_MEDIA_VOLUME_NOT_CONFIGURED";
    }

    private static void readAndSetLowRamDevice() {
        boolean isLowRamDevice = ActivityManager.isLowRamDeviceStatic();
        long totalMemory = 1073741824;
        try {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            ActivityManager.getService().getMemoryInfo(info);
            totalMemory = info.totalMem;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot obtain MemoryInfo from ActivityManager, assume low memory device");
            isLowRamDevice = true;
        }
        int status = AudioSystem.setLowRamDevice(isLowRamDevice, totalMemory);
        if (status != 0) {
            Log.w(TAG, "AudioFlinger informed of device's low RAM attribute; status " + status);
        }
    }

    private void enforceVolumeController(String action) {
        Context context = this.mContext;
        context.enforceCallingOrSelfPermission("android.permission.STATUS_BAR_SERVICE", "Only SystemUI can " + action);
    }

    public void setVolumeController(final IVolumeController controller) {
        enforceVolumeController("set the volume controller");
        if (this.mVolumeController.isSameBinder(controller)) {
            return;
        }
        this.mVolumeController.postDismiss();
        if (controller != null) {
            try {
                controller.asBinder().linkToDeath(new IBinder.DeathRecipient() { // from class: com.android.server.audio.AudioService.6
                    @Override // android.os.IBinder.DeathRecipient
                    public void binderDied() {
                        if (AudioService.this.mVolumeController.isSameBinder(controller)) {
                            Log.w(AudioService.TAG, "Current remote volume controller died, unregistering");
                            AudioService.this.setVolumeController(null);
                        }
                    }
                }, 0);
            } catch (RemoteException e) {
            }
        }
        this.mVolumeController.setController(controller);
        Log.d(TAG, "Volume controller: " + this.mVolumeController);
    }

    public void notifyVolumeControllerVisible(IVolumeController controller, boolean visible) {
        enforceVolumeController("notify about volume controller visibility");
        if (!this.mVolumeController.isSameBinder(controller)) {
            return;
        }
        this.mVolumeController.setVisible(visible);
        Log.d(TAG, "Volume controller visible: " + visible);
    }

    public void setVolumePolicy(VolumePolicy policy) {
        enforceVolumeController("set volume policy");
        if (policy != null && !policy.equals(this.mVolumePolicy)) {
            this.mVolumePolicy = policy;
            Log.d(TAG, "Volume policy changed: " + this.mVolumePolicy);
        }
    }

    /* loaded from: classes.dex */
    public static class VolumeController {
        private static final String TAG = "VolumeController";
        private IVolumeController mController;
        private int mLongPressTimeout;
        private long mNextLongPress;
        private boolean mVisible;

        public void setController(IVolumeController controller) {
            this.mController = controller;
            this.mVisible = false;
        }

        public void loadSettings(ContentResolver cr) {
            this.mLongPressTimeout = Settings.Secure.getIntForUser(cr, "long_press_timeout", 500, -2);
        }

        public boolean suppressAdjustment(int resolvedStream, int flags, boolean isMute) {
            if (isMute || resolvedStream != 3 || this.mController == null) {
                return false;
            }
            long now = SystemClock.uptimeMillis();
            if ((flags & 1) != 0 && !this.mVisible) {
                if (this.mNextLongPress < now) {
                    this.mNextLongPress = this.mLongPressTimeout + now;
                }
                return true;
            }
            long j = this.mNextLongPress;
            if (j <= 0) {
                return false;
            }
            if (now > j) {
                this.mNextLongPress = 0L;
                return false;
            }
            return true;
        }

        public void setVisible(boolean visible) {
            this.mVisible = visible;
        }

        public boolean isSameBinder(IVolumeController controller) {
            return Objects.equals(asBinder(), binder(controller));
        }

        public IBinder asBinder() {
            return binder(this.mController);
        }

        private static IBinder binder(IVolumeController controller) {
            if (controller == null) {
                return null;
            }
            return controller.asBinder();
        }

        public String toString() {
            return "VolumeController(" + asBinder() + ",mVisible=" + this.mVisible + ")";
        }

        public void postDisplaySafeVolumeWarning(int flags) {
            IVolumeController iVolumeController = this.mController;
            if (iVolumeController == null) {
                return;
            }
            try {
                iVolumeController.displaySafeVolumeWarning(flags);
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling displaySafeVolumeWarning", e);
            }
        }

        public void postVolumeChanged(int streamType, int flags) {
            IVolumeController iVolumeController = this.mController;
            if (iVolumeController == null) {
                return;
            }
            try {
                iVolumeController.volumeChanged(streamType, flags);
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling volumeChanged", e);
            }
        }

        public void postMasterMuteChanged(int flags) {
            IVolumeController iVolumeController = this.mController;
            if (iVolumeController == null) {
                return;
            }
            try {
                iVolumeController.masterMuteChanged(flags);
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling masterMuteChanged", e);
            }
        }

        public void setLayoutDirection(int layoutDirection) {
            IVolumeController iVolumeController = this.mController;
            if (iVolumeController == null) {
                return;
            }
            try {
                iVolumeController.setLayoutDirection(layoutDirection);
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling setLayoutDirection", e);
            }
        }

        public void postDismiss() {
            IVolumeController iVolumeController = this.mController;
            if (iVolumeController == null) {
                return;
            }
            try {
                iVolumeController.dismiss();
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling dismiss", e);
            }
        }

        public void setA11yMode(int a11yMode) {
            IVolumeController iVolumeController = this.mController;
            if (iVolumeController == null) {
                return;
            }
            try {
                iVolumeController.setA11yMode(a11yMode);
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling setA11Mode", e);
            }
        }
    }

    /* loaded from: classes.dex */
    final class AudioServiceInternal extends AudioManagerInternal {
        AudioServiceInternal() {
        }

        public void setRingerModeDelegate(AudioManagerInternal.RingerModeDelegate delegate) {
            AudioService.this.mRingerModeDelegate = delegate;
            if (AudioService.this.mRingerModeDelegate != null) {
                synchronized (AudioService.this.mSettingsLock) {
                    AudioService.this.updateRingerAndZenModeAffectedStreams();
                }
                setRingerModeInternal(getRingerModeInternal(), "AS.AudioService.setRingerModeDelegate");
            }
        }

        public void adjustSuggestedStreamVolumeForUid(int streamType, int direction, int flags, String callingPackage, int uid) {
            AudioService.this.adjustSuggestedStreamVolume(direction, streamType, flags, callingPackage, callingPackage, uid);
        }

        public void adjustStreamVolumeForUid(int streamType, int direction, int flags, String callingPackage, int uid) {
            if (direction != 0) {
                AudioEventLogger audioEventLogger = AudioService.sVolumeLogger;
                audioEventLogger.log(new AudioServiceEvents.VolumeEvent(5, streamType, direction, flags, callingPackage + " uid:" + uid));
            }
            AudioService.this.adjustStreamVolume(streamType, direction, flags, callingPackage, callingPackage, uid);
        }

        public void setStreamVolumeForUid(int streamType, int direction, int flags, String callingPackage, int uid) {
            AudioService.this.setStreamVolume(streamType, direction, flags, callingPackage, callingPackage, uid);
        }

        public int getRingerModeInternal() {
            return AudioService.this.getRingerModeInternal();
        }

        public void setRingerModeInternal(int ringerMode, String caller) {
            AudioService.this.setRingerModeInternal(ringerMode, caller);
        }

        public void silenceRingerModeInternal(String caller) {
            AudioService.this.silenceRingerModeInternal(caller);
        }

        public void updateRingerModeAffectedStreamsInternal() {
            synchronized (AudioService.this.mSettingsLock) {
                if (AudioService.this.updateRingerAndZenModeAffectedStreams()) {
                    AudioService.this.setRingerModeInt(getRingerModeInternal(), false);
                }
            }
        }

        /* JADX WARN: Removed duplicated region for block: B:15:0x0031 A[Catch: all -> 0x0063, LOOP:0: B:15:0x0031->B:20:0x004a, LOOP_START, PHI: r2 
          PHI: (r2v2 'i' int) = (r2v0 'i' int), (r2v3 'i' int) binds: [B:14:0x002e, B:20:0x004a] A[DONT_GENERATE, DONT_INLINE], TryCatch #0 {, blocks: (B:4:0x0007, B:6:0x000d, B:23:0x0058, B:24:0x0061, B:7:0x0014, B:9:0x001d, B:15:0x0031, B:17:0x003a, B:20:0x004a, B:22:0x004f), top: B:29:0x0007 }] */
        /* JADX WARN: Removed duplicated region for block: B:22:0x004f A[Catch: all -> 0x0063, TryCatch #0 {, blocks: (B:4:0x0007, B:6:0x000d, B:23:0x0058, B:24:0x0061, B:7:0x0014, B:9:0x001d, B:15:0x0031, B:17:0x003a, B:20:0x004a, B:22:0x004f), top: B:29:0x0007 }] */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public void setAccessibilityServiceUids(android.util.IntArray r6) {
            /*
                r5 = this;
                com.android.server.audio.AudioService r0 = com.android.server.audio.AudioService.this
                java.lang.Object r0 = com.android.server.audio.AudioService.access$9500(r0)
                monitor-enter(r0)
                int r1 = r6.size()     // Catch: java.lang.Throwable -> L63
                if (r1 != 0) goto L14
                com.android.server.audio.AudioService r1 = com.android.server.audio.AudioService.this     // Catch: java.lang.Throwable -> L63
                r2 = 0
                com.android.server.audio.AudioService.access$9602(r1, r2)     // Catch: java.lang.Throwable -> L63
                goto L58
            L14:
                com.android.server.audio.AudioService r1 = com.android.server.audio.AudioService.this     // Catch: java.lang.Throwable -> L63
                int[] r1 = com.android.server.audio.AudioService.access$9600(r1)     // Catch: java.lang.Throwable -> L63
                r2 = 0
                if (r1 == 0) goto L2d
                com.android.server.audio.AudioService r1 = com.android.server.audio.AudioService.this     // Catch: java.lang.Throwable -> L63
                int[] r1 = com.android.server.audio.AudioService.access$9600(r1)     // Catch: java.lang.Throwable -> L63
                int r1 = r1.length     // Catch: java.lang.Throwable -> L63
                int r3 = r6.size()     // Catch: java.lang.Throwable -> L63
                if (r1 == r3) goto L2b
                goto L2d
            L2b:
                r1 = r2
                goto L2e
            L2d:
                r1 = 1
            L2e:
                if (r1 != 0) goto L4d
            L31:
                com.android.server.audio.AudioService r3 = com.android.server.audio.AudioService.this     // Catch: java.lang.Throwable -> L63
                int[] r3 = com.android.server.audio.AudioService.access$9600(r3)     // Catch: java.lang.Throwable -> L63
                int r3 = r3.length     // Catch: java.lang.Throwable -> L63
                if (r2 >= r3) goto L4d
                int r3 = r6.get(r2)     // Catch: java.lang.Throwable -> L63
                com.android.server.audio.AudioService r4 = com.android.server.audio.AudioService.this     // Catch: java.lang.Throwable -> L63
                int[] r4 = com.android.server.audio.AudioService.access$9600(r4)     // Catch: java.lang.Throwable -> L63
                r4 = r4[r2]     // Catch: java.lang.Throwable -> L63
                if (r3 == r4) goto L4a
                r1 = 1
                goto L4d
            L4a:
                int r2 = r2 + 1
                goto L31
            L4d:
                if (r1 == 0) goto L58
                com.android.server.audio.AudioService r2 = com.android.server.audio.AudioService.this     // Catch: java.lang.Throwable -> L63
                int[] r3 = r6.toArray()     // Catch: java.lang.Throwable -> L63
                com.android.server.audio.AudioService.access$9602(r2, r3)     // Catch: java.lang.Throwable -> L63
            L58:
                com.android.server.audio.AudioService r1 = com.android.server.audio.AudioService.this     // Catch: java.lang.Throwable -> L63
                int[] r1 = com.android.server.audio.AudioService.access$9600(r1)     // Catch: java.lang.Throwable -> L63
                android.media.AudioSystem.setA11yServicesUids(r1)     // Catch: java.lang.Throwable -> L63
                monitor-exit(r0)     // Catch: java.lang.Throwable -> L63
                return
            L63:
                r1 = move-exception
                monitor-exit(r0)     // Catch: java.lang.Throwable -> L63
                throw r1
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.audio.AudioService.AudioServiceInternal.setAccessibilityServiceUids(android.util.IntArray):void");
        }
    }

    public String registerAudioPolicy(AudioPolicyConfig policyConfig, IAudioPolicyCallback pcb, boolean hasFocusListener, boolean isFocusPolicy, boolean isTestFocusPolicy, boolean isVolumeController, IMediaProjection projection) {
        HashMap<IBinder, AudioPolicyProxy> hashMap;
        AudioSystem.setDynamicPolicyCallback(this.mDynPolicyCallback);
        Log.d(TAG, "registerAudioPolicy  " + policyConfig.toString() + "isVolumeController:" + isVolumeController);
        if (!isPolicyRegisterAllowed(policyConfig, isFocusPolicy || isTestFocusPolicy || hasFocusListener, isVolumeController, projection)) {
            Slog.w(TAG, "Permission denied to register audio policy for pid " + Binder.getCallingPid() + " / uid " + Binder.getCallingUid() + ", need MODIFY_AUDIO_ROUTING or MediaProjection that can project audio");
            return null;
        }
        AudioEventLogger audioEventLogger = this.mDynPolicyLogger;
        audioEventLogger.log(new AudioEventLogger.StringEvent("registerAudioPolicy for " + pcb.asBinder() + " with config:" + policyConfig).printLog(TAG));
        HashMap<IBinder, AudioPolicyProxy> hashMap2 = this.mAudioPolicies;
        synchronized (hashMap2) {
            try {
                try {
                    if (this.mAudioPolicies.containsKey(pcb.asBinder())) {
                        Slog.e(TAG, "Cannot re-register policy");
                        return null;
                    }
                    try {
                        hashMap = hashMap2;
                        try {
                            AudioPolicyProxy app = new AudioPolicyProxy(policyConfig, pcb, hasFocusListener, isFocusPolicy, isTestFocusPolicy, isVolumeController, projection);
                            pcb.asBinder().linkToDeath(app, 0);
                            String regId = app.getRegistrationId();
                            this.mAudioPolicies.put(pcb.asBinder(), app);
                            return regId;
                        } catch (RemoteException e) {
                            e = e;
                            Slog.w(TAG, "Audio policy registration failed, could not link to " + pcb + " binder death", e);
                            return null;
                        } catch (IllegalStateException e2) {
                            e = e2;
                            Slog.w(TAG, "Audio policy registration failed for binder " + pcb, e);
                            return null;
                        }
                    } catch (RemoteException e3) {
                        e = e3;
                        hashMap = hashMap2;
                    } catch (IllegalStateException e4) {
                        e = e4;
                        hashMap = hashMap2;
                    }
                } catch (Throwable th) {
                    e = th;
                    throw e;
                }
            } catch (Throwable th2) {
                e = th2;
                throw e;
            }
        }
    }

    private boolean isPolicyRegisterAllowed(AudioPolicyConfig policyConfig, boolean hasFocusAccess, boolean isVolumeController, IMediaProjection projection) {
        boolean requireValidProjection = false;
        boolean requireCaptureAudioOrMediaOutputPerm = false;
        boolean requireModifyRouting = false;
        if (hasFocusAccess || isVolumeController) {
            requireModifyRouting = false | true;
        } else if (policyConfig.getMixes().isEmpty()) {
            requireModifyRouting = false | true;
        }
        Iterator it = policyConfig.getMixes().iterator();
        while (it.hasNext()) {
            AudioMix mix = (AudioMix) it.next();
            if (mix.getRule().allowPrivilegedPlaybackCapture()) {
                requireCaptureAudioOrMediaOutputPerm |= true;
                String error = AudioMix.canBeUsedForPrivilegedCapture(mix.getFormat());
                if (error != null) {
                    Log.e(TAG, error);
                    return false;
                }
            }
            if (mix.getRouteFlags() == 3 && projection != null) {
                requireValidProjection |= true;
            } else {
                requireModifyRouting |= true;
            }
        }
        if (requireCaptureAudioOrMediaOutputPerm && !callerHasPermission("android.permission.CAPTURE_MEDIA_OUTPUT") && !callerHasPermission("android.permission.CAPTURE_AUDIO_OUTPUT")) {
            Log.e(TAG, "Privileged audio capture requires CAPTURE_MEDIA_OUTPUT or CAPTURE_AUDIO_OUTPUT system permission");
            return false;
        } else if (!requireValidProjection || canProjectAudio(projection)) {
            if (requireModifyRouting && !callerHasPermission("android.permission.MODIFY_AUDIO_ROUTING")) {
                Log.e(TAG, "Can not capture audio without MODIFY_AUDIO_ROUTING");
                return false;
            }
            return true;
        } else {
            return false;
        }
    }

    private boolean callerHasPermission(String permission) {
        return this.mContext.checkCallingPermission(permission) == 0;
    }

    private boolean canProjectAudio(IMediaProjection projection) {
        if (projection == null) {
            Log.e(TAG, "MediaProjection is null");
            return false;
        }
        IMediaProjectionManager projectionService = getProjectionService();
        if (projectionService == null) {
            Log.e(TAG, "Can't get service IMediaProjectionManager");
            return false;
        }
        try {
            if (!projectionService.isValidMediaProjection(projection)) {
                Log.w(TAG, "App passed invalid MediaProjection token");
                return false;
            }
            try {
                if (!projection.canProjectAudio()) {
                    Log.w(TAG, "App passed MediaProjection that can not project audio");
                    return false;
                }
                return true;
            } catch (RemoteException e) {
                Log.e(TAG, "Can't call .canProjectAudio() on valid IMediaProjection" + projection.asBinder(), e);
                return false;
            }
        } catch (RemoteException e2) {
            Log.e(TAG, "Can't call .isValidMediaProjection() on IMediaProjectionManager" + projectionService.asBinder(), e2);
            return false;
        }
    }

    private IMediaProjectionManager getProjectionService() {
        if (this.mProjectionService == null) {
            IBinder b = ServiceManager.getService("media_projection");
            this.mProjectionService = IMediaProjectionManager.Stub.asInterface(b);
        }
        return this.mProjectionService;
    }

    public void unregisterAudioPolicyAsync(IAudioPolicyCallback pcb) {
        unregisterAudioPolicy(pcb);
    }

    public void unregisterAudioPolicy(IAudioPolicyCallback pcb) {
        if (pcb == null) {
            return;
        }
        unregisterAudioPolicyInt(pcb);
    }

    private void unregisterAudioPolicyInt(IAudioPolicyCallback pcb) {
        AudioEventLogger audioEventLogger = this.mDynPolicyLogger;
        audioEventLogger.log(new AudioEventLogger.StringEvent("unregisterAudioPolicyAsync for " + pcb.asBinder()).printLog(TAG));
        synchronized (this.mAudioPolicies) {
            AudioPolicyProxy app = this.mAudioPolicies.remove(pcb.asBinder());
            if (app == null) {
                Slog.w(TAG, "Trying to unregister unknown audio policy for pid " + Binder.getCallingPid() + " / uid " + Binder.getCallingUid());
                return;
            }
            pcb.asBinder().unlinkToDeath(app, 0);
            app.release();
        }
    }

    @GuardedBy({"mAudioPolicies"})
    private AudioPolicyProxy checkUpdateForPolicy(IAudioPolicyCallback pcb, String errorMsg) {
        boolean hasPermissionForPolicy = this.mContext.checkCallingPermission("android.permission.MODIFY_AUDIO_ROUTING") == 0;
        if (!hasPermissionForPolicy) {
            Slog.w(TAG, errorMsg + " for pid " + Binder.getCallingPid() + " / uid " + Binder.getCallingUid() + ", need MODIFY_AUDIO_ROUTING");
            return null;
        }
        AudioPolicyProxy app = this.mAudioPolicies.get(pcb.asBinder());
        if (app == null) {
            Slog.w(TAG, errorMsg + " for pid " + Binder.getCallingPid() + " / uid " + Binder.getCallingUid() + ", unregistered policy");
            return null;
        }
        return app;
    }

    public int addMixForPolicy(AudioPolicyConfig policyConfig, IAudioPolicyCallback pcb) {
        synchronized (this.mAudioPolicies) {
            AudioPolicyProxy app = checkUpdateForPolicy(pcb, "Cannot add AudioMix in audio policy");
            int i = -1;
            if (app == null) {
                return -1;
            }
            if (app.addMixes(policyConfig.getMixes()) == 0) {
                i = 0;
            }
            return i;
        }
    }

    public int removeMixForPolicy(AudioPolicyConfig policyConfig, IAudioPolicyCallback pcb) {
        synchronized (this.mAudioPolicies) {
            AudioPolicyProxy app = checkUpdateForPolicy(pcb, "Cannot add AudioMix in audio policy");
            int i = -1;
            if (app == null) {
                return -1;
            }
            if (app.removeMixes(policyConfig.getMixes()) == 0) {
                i = 0;
            }
            return i;
        }
    }

    public int setUidDeviceAffinity(IAudioPolicyCallback pcb, int uid, int[] deviceTypes, String[] deviceAddresses) {
        synchronized (this.mAudioPolicies) {
            AudioPolicyProxy app = checkUpdateForPolicy(pcb, "Cannot change device affinity in audio policy");
            if (app == null) {
                return -1;
            }
            if (!app.hasMixRoutedToDevices(deviceTypes, deviceAddresses)) {
                return -1;
            }
            return app.setUidDeviceAffinities(uid, deviceTypes, deviceAddresses);
        }
    }

    public int removeUidDeviceAffinity(IAudioPolicyCallback pcb, int uid) {
        synchronized (this.mAudioPolicies) {
            AudioPolicyProxy app = checkUpdateForPolicy(pcb, "Cannot remove device affinity in audio policy");
            if (app == null) {
                return -1;
            }
            return app.removeUidDeviceAffinities(uid);
        }
    }

    public int setFocusPropertiesForPolicy(int duckingBehavior, IAudioPolicyCallback pcb) {
        synchronized (this.mAudioPolicies) {
            AudioPolicyProxy app = checkUpdateForPolicy(pcb, "Cannot change audio policy focus properties");
            if (app == null) {
                return -1;
            }
            if (!this.mAudioPolicies.containsKey(pcb.asBinder())) {
                Slog.e(TAG, "Cannot change audio policy focus properties, unregistered policy");
                return -1;
            }
            boolean z = true;
            if (duckingBehavior == 1) {
                for (AudioPolicyProxy policy : this.mAudioPolicies.values()) {
                    if (policy.mFocusDuckBehavior == 1) {
                        Slog.e(TAG, "Cannot change audio policy ducking behavior, already handled");
                        return -1;
                    }
                }
            }
            app.mFocusDuckBehavior = duckingBehavior;
            MediaFocusControl mediaFocusControl = this.mMediaFocusControl;
            if (duckingBehavior != 1) {
                z = false;
            }
            mediaFocusControl.setDuckingInExtPolicyAvailable(z);
            return 0;
        }
    }

    public boolean hasRegisteredDynamicPolicy() {
        boolean z;
        synchronized (this.mAudioPolicies) {
            z = !this.mAudioPolicies.isEmpty();
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setExtVolumeController(IAudioPolicyCallback apc) {
        if (!this.mContext.getResources().getBoolean(17891466)) {
            Log.e(TAG, "Cannot set external volume controller: device not set for volume keys handled in PhoneWindowManager");
            return;
        }
        synchronized (this.mExtVolumeControllerLock) {
            if (this.mExtVolumeController != null && !this.mExtVolumeController.asBinder().pingBinder()) {
                Log.e(TAG, "Cannot set external volume controller: existing controller");
            }
            this.mExtVolumeController = apc;
        }
    }

    private void dumpAudioPolicies(PrintWriter pw) {
        pw.println("\nAudio policies:");
        synchronized (this.mAudioPolicies) {
            for (AudioPolicyProxy policy : this.mAudioPolicies.values()) {
                pw.println(policy.toLogFriendlyString());
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onDynPolicyMixStateUpdate(String regId, int state) {
        synchronized (this.mAudioPolicies) {
            for (AudioPolicyProxy policy : this.mAudioPolicies.values()) {
                Iterator it = policy.getMixes().iterator();
                while (it.hasNext()) {
                    AudioMix mix = (AudioMix) it.next();
                    if (mix.getRegistration().equals(regId)) {
                        try {
                            policy.mPolicyCallback.notifyMixStateUpdate(regId, state);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Can't call notifyMixStateUpdate() on IAudioPolicyCallback " + policy.mPolicyCallback.asBinder(), e);
                        }
                        return;
                    }
                }
            }
        }
    }

    public void registerRecordingCallback(IRecordingConfigDispatcher rcdb) {
        boolean isPrivileged = this.mContext.checkCallingPermission("android.permission.MODIFY_AUDIO_ROUTING") == 0;
        this.mRecordMonitor.registerRecordingCallback(rcdb, isPrivileged);
    }

    public void unregisterRecordingCallback(IRecordingConfigDispatcher rcdb) {
        this.mRecordMonitor.unregisterRecordingCallback(rcdb);
    }

    public List<AudioRecordingConfiguration> getActiveRecordingConfigurations() {
        boolean isPrivileged = this.mContext.checkCallingPermission("android.permission.MODIFY_AUDIO_ROUTING") == 0;
        return this.mRecordMonitor.getActiveRecordingConfigurations(isPrivileged);
    }

    public int trackRecorder(IBinder recorder) {
        return this.mRecordMonitor.trackRecorder(recorder);
    }

    public void recorderEvent(int riid, int event) {
        this.mRecordMonitor.recorderEvent(riid, event);
    }

    public void releaseRecorder(int riid) {
        this.mRecordMonitor.releaseRecorder(riid);
    }

    public void disableRingtoneSync(int userId) {
        int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId != userId) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "disable sound settings syncing for another profile");
        }
        long token = Binder.clearCallingIdentity();
        try {
            Settings.Secure.putIntForUser(this.mContentResolver, "sync_parent_sounds", 0, userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void registerPlaybackCallback(IPlaybackConfigDispatcher pcdb) {
        boolean isPrivileged = this.mContext.checkCallingOrSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") == 0;
        this.mPlaybackMonitor.registerPlaybackCallback(pcdb, isPrivileged);
    }

    public void unregisterPlaybackCallback(IPlaybackConfigDispatcher pcdb) {
        this.mPlaybackMonitor.unregisterPlaybackCallback(pcdb);
    }

    public List<AudioPlaybackConfiguration> getActivePlaybackConfigurations() {
        boolean isPrivileged = this.mContext.checkCallingOrSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") == 0;
        return this.mPlaybackMonitor.getActivePlaybackConfigurations(isPrivileged);
    }

    public int trackPlayer(PlayerBase.PlayerIdCard pic) {
        return this.mPlaybackMonitor.trackPlayer(pic);
    }

    public void playerAttributes(int piid, AudioAttributes attr) {
        this.mPlaybackMonitor.playerAttributes(piid, attr, Binder.getCallingUid());
    }

    public void playerEvent(int piid, int event) {
        this.mPlaybackMonitor.playerEvent(piid, event, Binder.getCallingUid());
    }

    public void playerHasOpPlayAudio(int piid, boolean hasOpPlayAudio) {
        this.mPlaybackMonitor.playerHasOpPlayAudio(piid, hasOpPlayAudio, Binder.getCallingUid());
    }

    public void releasePlayer(int piid) {
        this.mPlaybackMonitor.releasePlayer(piid, Binder.getCallingUid());
    }

    public int setAllowedCapturePolicy(int capturePolicy) {
        int result;
        int callingUid = Binder.getCallingUid();
        int flags = AudioAttributes.capturePolicyToFlags(capturePolicy, 0);
        long identity = Binder.clearCallingIdentity();
        synchronized (this.mPlaybackMonitor) {
            result = AudioSystem.setAllowedCapturePolicy(callingUid, flags);
            if (result == 0) {
                this.mPlaybackMonitor.setAllowedCapturePolicy(callingUid, capturePolicy);
            }
            Binder.restoreCallingIdentity(identity);
        }
        return result;
    }

    public int getAllowedCapturePolicy() {
        int callingUid = Binder.getCallingUid();
        long identity = Binder.clearCallingIdentity();
        int capturePolicy = this.mPlaybackMonitor.getAllowedCapturePolicy(callingUid);
        Binder.restoreCallingIdentity(identity);
        return capturePolicy;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class AudioDeviceArray {
        final String[] mDeviceAddresses;
        final int[] mDeviceTypes;

        AudioDeviceArray(int[] types, String[] addresses) {
            this.mDeviceTypes = types;
            this.mDeviceAddresses = addresses;
        }
    }

    /* loaded from: classes.dex */
    public class AudioPolicyProxy extends AudioPolicyConfig implements IBinder.DeathRecipient {
        private static final String TAG = "AudioPolicyProxy";
        int mFocusDuckBehavior;
        final boolean mHasFocusListener;
        boolean mIsFocusPolicy;
        boolean mIsTestFocusPolicy;
        final boolean mIsVolumeController;
        final IAudioPolicyCallback mPolicyCallback;
        final IMediaProjection mProjection;
        UnregisterOnStopCallback mProjectionCallback;
        final HashMap<Integer, AudioDeviceArray> mUidDeviceAffinities;

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes.dex */
        public final class UnregisterOnStopCallback extends IMediaProjectionCallback.Stub {
            private UnregisterOnStopCallback() {
            }

            public void onStop() {
                AudioService.this.unregisterAudioPolicyAsync(AudioPolicyProxy.this.mPolicyCallback);
            }
        }

        AudioPolicyProxy(AudioPolicyConfig config, IAudioPolicyCallback token, boolean hasFocusListener, boolean isFocusPolicy, boolean isTestFocusPolicy, boolean isVolumeController, IMediaProjection projection) {
            super(config);
            this.mUidDeviceAffinities = new HashMap<>();
            this.mFocusDuckBehavior = 0;
            this.mIsFocusPolicy = false;
            this.mIsTestFocusPolicy = false;
            setRegistration(new String(config.hashCode() + ":ap:" + AudioService.access$9708(AudioService.this)));
            this.mPolicyCallback = token;
            this.mHasFocusListener = hasFocusListener;
            this.mIsVolumeController = isVolumeController;
            this.mProjection = projection;
            if (this.mHasFocusListener) {
                AudioService.this.mMediaFocusControl.addFocusFollower(this.mPolicyCallback);
                if (isFocusPolicy) {
                    this.mIsFocusPolicy = true;
                    this.mIsTestFocusPolicy = isTestFocusPolicy;
                    AudioService.this.mMediaFocusControl.setFocusPolicy(this.mPolicyCallback, this.mIsTestFocusPolicy);
                }
            }
            if (this.mIsVolumeController) {
                AudioService.this.setExtVolumeController(this.mPolicyCallback);
            }
            if (this.mProjection != null) {
                this.mProjectionCallback = new UnregisterOnStopCallback();
                try {
                    this.mProjection.registerCallback(this.mProjectionCallback);
                } catch (RemoteException e) {
                    release();
                    throw new IllegalStateException("MediaProjection callback registration failed, could not link to " + projection + " binder death", e);
                }
            }
            int status = connectMixes();
            if (status != 0) {
                release();
                throw new IllegalStateException("Could not connect mix, error: " + status);
            }
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            synchronized (AudioService.this.mAudioPolicies) {
                Log.i(TAG, "audio policy " + this.mPolicyCallback + " died");
                release();
                AudioService.this.mAudioPolicies.remove(this.mPolicyCallback.asBinder());
            }
            if (this.mIsVolumeController) {
                synchronized (AudioService.this.mExtVolumeControllerLock) {
                    AudioService.this.mExtVolumeController = null;
                }
            }
        }

        String getRegistrationId() {
            return getRegistration();
        }

        void release() {
            if (this.mIsFocusPolicy) {
                AudioService.this.mMediaFocusControl.unsetFocusPolicy(this.mPolicyCallback, this.mIsTestFocusPolicy);
            }
            if (this.mFocusDuckBehavior == 1) {
                AudioService.this.mMediaFocusControl.setDuckingInExtPolicyAvailable(false);
            }
            if (this.mHasFocusListener) {
                AudioService.this.mMediaFocusControl.removeFocusFollower(this.mPolicyCallback);
            }
            UnregisterOnStopCallback unregisterOnStopCallback = this.mProjectionCallback;
            if (unregisterOnStopCallback != null) {
                try {
                    this.mProjection.unregisterCallback(unregisterOnStopCallback);
                } catch (RemoteException e) {
                    Log.e(TAG, "Fail to unregister Audiopolicy callback from MediaProjection");
                }
            }
            long identity = Binder.clearCallingIdentity();
            Log.i(TAG, "release()  registerPolicyMixes(mMixes, false)");
            AudioSystem.registerPolicyMixes(this.mMixes, false);
            Binder.restoreCallingIdentity(identity);
        }

        boolean hasMixAffectingUsage(int usage, int excludedFlags) {
            Iterator it = this.mMixes.iterator();
            while (it.hasNext()) {
                AudioMix mix = (AudioMix) it.next();
                if (mix.isAffectingUsage(usage) && (mix.getRouteFlags() & excludedFlags) != excludedFlags) {
                    return true;
                }
            }
            return false;
        }

        boolean hasMixRoutedToDevices(int[] deviceTypes, String[] deviceAddresses) {
            for (int i = 0; i < deviceTypes.length; i++) {
                boolean hasDevice = false;
                Iterator it = this.mMixes.iterator();
                while (true) {
                    if (!it.hasNext()) {
                        break;
                    }
                    AudioMix mix = (AudioMix) it.next();
                    if (mix.isRoutedToDevice(deviceTypes[i], deviceAddresses[i])) {
                        hasDevice = true;
                        break;
                    }
                }
                if (!hasDevice) {
                    return false;
                }
            }
            return true;
        }

        int addMixes(ArrayList<AudioMix> mixes) {
            int registerPolicyMixes;
            synchronized (this.mMixes) {
                Log.i(TAG, "addMixes() ");
                AudioSystem.registerPolicyMixes(this.mMixes, false);
                add(mixes);
                registerPolicyMixes = AudioSystem.registerPolicyMixes(this.mMixes, true);
            }
            return registerPolicyMixes;
        }

        int removeMixes(ArrayList<AudioMix> mixes) {
            int registerPolicyMixes;
            synchronized (this.mMixes) {
                Log.i(TAG, "removeMixes() ");
                AudioSystem.registerPolicyMixes(this.mMixes, false);
                remove(mixes);
                registerPolicyMixes = AudioSystem.registerPolicyMixes(this.mMixes, true);
            }
            return registerPolicyMixes;
        }

        int connectMixes() {
            Log.i(TAG, "connectMixes() ");
            long identity = Binder.clearCallingIdentity();
            AudioSystem.registerPolicyMixes(this.mMixes, false);
            int status = AudioSystem.registerPolicyMixes(this.mMixes, true);
            Binder.restoreCallingIdentity(identity);
            return status;
        }

        int setUidDeviceAffinities(int uid, int[] types, String[] addresses) {
            Integer Uid = new Integer(uid);
            if (this.mUidDeviceAffinities.remove(Uid) != null) {
                long identity = Binder.clearCallingIdentity();
                int res = AudioSystem.removeUidDeviceAffinities(uid);
                Binder.restoreCallingIdentity(identity);
                if (res != 0) {
                    Log.e(TAG, "AudioSystem. removeUidDeviceAffinities(" + uid + ") failed,  cannot call AudioSystem.setUidDeviceAffinities");
                    return -1;
                }
            }
            long identity2 = Binder.clearCallingIdentity();
            int res2 = AudioSystem.setUidDeviceAffinities(uid, types, addresses);
            Binder.restoreCallingIdentity(identity2);
            if (res2 == 0) {
                this.mUidDeviceAffinities.put(Uid, new AudioDeviceArray(types, addresses));
                return 0;
            }
            Log.e(TAG, "AudioSystem. setUidDeviceAffinities(" + uid + ") failed");
            return -1;
        }

        int removeUidDeviceAffinities(int uid) {
            if (this.mUidDeviceAffinities.remove(new Integer(uid)) != null) {
                long identity = Binder.clearCallingIdentity();
                int res = AudioSystem.removeUidDeviceAffinities(uid);
                Binder.restoreCallingIdentity(identity);
                if (res == 0) {
                    return 0;
                }
            }
            Log.e(TAG, "AudioSystem. removeUidDeviceAffinities failed");
            return -1;
        }

        public String toLogFriendlyString() {
            String textDump = super.toLogFriendlyString();
            String textDump2 = (textDump + " Proxy:\n") + "   is focus policy= " + this.mIsFocusPolicy + "\n";
            if (this.mIsFocusPolicy) {
                textDump2 = ((textDump2 + "     focus duck behaviour= " + this.mFocusDuckBehavior + "\n") + "     is test focus policy= " + this.mIsTestFocusPolicy + "\n") + "     has focus listener= " + this.mHasFocusListener + "\n";
            }
            return textDump2 + "   media projection= " + this.mProjection + "\n";
        }
    }

    public int dispatchFocusChange(AudioFocusInfo afi, int focusChange, IAudioPolicyCallback pcb) {
        int dispatchFocusChange;
        if (afi == null) {
            throw new IllegalArgumentException("Illegal null AudioFocusInfo");
        }
        if (pcb == null) {
            throw new IllegalArgumentException("Illegal null AudioPolicy callback");
        }
        synchronized (this.mAudioPolicies) {
            if (!this.mAudioPolicies.containsKey(pcb.asBinder())) {
                throw new IllegalStateException("Unregistered AudioPolicy for focus dispatch");
            }
            dispatchFocusChange = this.mMediaFocusControl.dispatchFocusChange(afi, focusChange);
        }
        return dispatchFocusChange;
    }

    public void setFocusRequestResultFromExtPolicy(AudioFocusInfo afi, int requestResult, IAudioPolicyCallback pcb) {
        if (afi == null) {
            throw new IllegalArgumentException("Illegal null AudioFocusInfo");
        }
        if (pcb == null) {
            throw new IllegalArgumentException("Illegal null AudioPolicy callback");
        }
        synchronized (this.mAudioPolicies) {
            if (!this.mAudioPolicies.containsKey(pcb.asBinder())) {
                throw new IllegalStateException("Unregistered AudioPolicy for external focus");
            }
            this.mMediaFocusControl.setFocusRequestResultFromExtPolicy(afi, requestResult);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class AsdProxy implements IBinder.DeathRecipient {
        private final IAudioServerStateDispatcher mAsd;

        AsdProxy(IAudioServerStateDispatcher asd) {
            this.mAsd = asd;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            synchronized (AudioService.this.mAudioServerStateListeners) {
                AudioService.this.mAudioServerStateListeners.remove(this.mAsd.asBinder());
            }
        }

        IAudioServerStateDispatcher callback() {
            return this.mAsd;
        }
    }

    private void checkMonitorAudioServerStatePermission() {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.MODIFY_PHONE_STATE") != 0 && this.mContext.checkCallingOrSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") != 0) {
            throw new SecurityException("Not allowed to monitor audioserver state");
        }
    }

    public void registerAudioServerStateDispatcher(IAudioServerStateDispatcher asd) {
        checkMonitorAudioServerStatePermission();
        synchronized (this.mAudioServerStateListeners) {
            if (this.mAudioServerStateListeners.containsKey(asd.asBinder())) {
                Slog.w(TAG, "Cannot re-register audio server state dispatcher");
                return;
            }
            AsdProxy asdp = new AsdProxy(asd);
            try {
                asd.asBinder().linkToDeath(asdp, 0);
            } catch (RemoteException e) {
            }
            this.mAudioServerStateListeners.put(asd.asBinder(), asdp);
        }
    }

    public void unregisterAudioServerStateDispatcher(IAudioServerStateDispatcher asd) {
        checkMonitorAudioServerStatePermission();
        synchronized (this.mAudioServerStateListeners) {
            AsdProxy asdp = this.mAudioServerStateListeners.remove(asd.asBinder());
            if (asdp == null) {
                Slog.w(TAG, "Trying to unregister unknown audioserver state dispatcher for pid " + Binder.getCallingPid() + " / uid " + Binder.getCallingUid());
                return;
            }
            asd.asBinder().unlinkToDeath(asdp, 0);
        }
    }

    public boolean isAudioServerRunning() {
        checkMonitorAudioServerStatePermission();
        return AudioSystem.checkAudioFlinger() == 0;
    }

    public void setSoundField(int mode, int xSound, int ySound) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setSoundField(mode, xSound, ySound);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.setSoundField(mode, xSound, ySound);
        }
    }

    public SoundField getSoundField(int mode) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.getSoundField(mode);
            }
        } else {
            xpAudio xpaudio = this.mXpAudio;
            if (xpaudio != null) {
                return xpaudio.getSoundField(mode);
            }
        }
        return new SoundField(0, 0);
    }

    public int getSoundEffectMode() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.getSoundEffectMode();
            }
            return -1;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.getSoundEffectMode();
        }
        return -1;
    }

    public void setSoundEffectMode(int mode) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setSoundEffectMode(mode);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.setSoundEffectMode(mode);
        }
    }

    public void setSoundEffectType(int mode, int type) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setSoundEffectType(mode, type);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.setSoundEffectType(mode, type);
        }
    }

    public int getSoundEffectType(int mode) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.getSoundEffectType(mode);
            }
            return -1;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.getSoundEffectType(mode);
        }
        return -1;
    }

    public void setSoundEffectScene(int mode, int type) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setSoundEffectScene(mode, type);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.setSoundEffectScene(mode, type);
        }
    }

    public int getSoundEffectScene(int mode) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.getSoundEffectScene(mode);
            }
            return -1;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.getSoundEffectScene(mode);
        }
        return -1;
    }

    public void setSoundEffectParms(int effectType, int nativeValue, int softValue, int innervationValue) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setSoundEffectParms(effectType, nativeValue, softValue, innervationValue);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.setSoundEffectParms(effectType, nativeValue, softValue, innervationValue);
        }
    }

    public SoundEffectParms getSoundEffectParms(int effectType, int modeType) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.getSoundEffectParms(effectType, modeType);
            }
        } else {
            xpAudio xpaudio = this.mXpAudio;
            if (xpaudio != null) {
                return xpaudio.getSoundEffectParms(effectType, modeType);
            }
        }
        return new SoundEffectParms(0, 0, 0);
    }

    public void setSoundSpeedLinkLevel(int level) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.setSoundSpeedLinkLevel(level);
        }
    }

    public int getSoundSpeedLinkLevel() {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            return xuiaudiopolicytrigger.getSoundSpeedLinkLevel();
        }
        return 0;
    }

    public void setDyn3dEffectLevel(int level) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.setDyn3dEffectLevel(level);
        }
    }

    public int getDyn3dEffectLevel() {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            return xuiaudiopolicytrigger.getDyn3dEffectLevel();
        }
        return 0;
    }

    public void setNavVolDecreaseEnable(boolean enable) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.setNavVolDecreaseEnable(enable);
        }
    }

    public boolean getNavVolDecreaseEnable() {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            return xuiaudiopolicytrigger.getNavVolDecreaseEnable();
        }
        return true;
    }

    public void setXpCustomizeEffect(int type, int value) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.setXpCustomizeEffect(type, value);
        }
    }

    public int getXpCustomizeEffect(int type) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            return xuiaudiopolicytrigger.getXpCustomizeEffect(type);
        }
        return 0;
    }

    public void flushXpCustomizeEffects(int[] values) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.flushXpCustomizeEffects(values);
        }
    }

    public void enableSystemSound() {
        synchronized (this.mSystemSoundLock) {
            this.mSystemSoundOn = true;
            SystemProperties.set(SYSTEM_SOUND_CONFIG, "true");
        }
    }

    public void disableSystemSound() {
        synchronized (this.mSystemSoundLock) {
            this.mSystemSoundOn = false;
            SystemProperties.set(SYSTEM_SOUND_CONFIG, "false");
        }
    }

    public boolean isSystemSoundEnabled() {
        boolean z;
        synchronized (this.mSystemSoundLock) {
            z = this.mSystemSoundOn;
        }
        return z;
    }

    public int applyUsage(int usage, int id, String pkgName) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.applyUsage(usage, id);
                return -1;
            }
            return -1;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            return xpAudioPolicy.applyUsage(usage, id, pkgName);
        }
        return -1;
    }

    public void releaseUsage(int usage, int id, String pkgName) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.releaseUsage(usage, id);
                return;
            }
            return;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            xpAudioPolicy.releaseUsage(usage, id, pkgName);
        }
    }

    public boolean isUsageActive(int usage) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.isUsageActive(usage);
            }
            return false;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            return xpAudioPolicy.isUsageActive(usage);
        }
        return false;
    }

    public void setStereoAlarm(boolean enable) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setStereoAlarm(enable);
                return;
            }
            return;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            xpAudioPolicy.setStereoAlarm(enable);
        }
    }

    public void setSpeechSurround(boolean enable) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setSpeechSurround(enable);
                return;
            }
            return;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            xpAudioPolicy.setSpeechSurround(enable);
        }
    }

    public void setMainDriver(boolean enable) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setMainDriver(enable);
                return;
            }
            return;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            xpAudioPolicy.setMainDriver(enable);
        }
    }

    public void setMainDriverMode(int mode) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setMainDriverMode(mode);
                return;
            }
            return;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            xpAudioPolicy.setMainDriverMode(mode);
        }
    }

    public int getMainDriverMode() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.getMainDriverMode();
            }
            return 0;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            return xpAudioPolicy.getMainDriverMode();
        }
        return 0;
    }

    public void setRingtoneSessionId(int streamType, int sessionId, String pkgName) {
        Log.i(TAG, "setRingtoneSessionId");
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setRingtoneSessionId(streamType, sessionId, pkgName);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.setRingtoneSessionId(streamType, sessionId, pkgName);
        }
    }

    public void setBanVolumeChangeMode(int streamType, int mode, String pkgName) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setBanVolumeChangeMode(streamType, mode, pkgName);
                return;
            }
            return;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            xpAudioPolicy.setBanVolumeChangeMode(streamType, mode, pkgName);
        }
    }

    public int getBanVolumeChangeMode(int streamType) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.getBanVolumeChangeMode(streamType);
            }
            return 0;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            return xpAudioPolicy.getBanVolumeChangeMode(streamType);
        }
        return 0;
    }

    public void setBtHeadPhone(boolean enable) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setBtHeadPhone(enable);
                return;
            }
            return;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            xpAudioPolicy.setBtHeadPhone(enable);
        }
    }

    public boolean isStereoAlarmOn() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.isStereoAlarmOn();
            }
            return false;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            return xpAudioPolicy.isStereoAlarmOn();
        }
        return false;
    }

    public boolean isSpeechSurroundOn() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.isSpeechSurroundOn();
            }
            return false;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            return xpAudioPolicy.isSpeechSurroundOn();
        }
        return false;
    }

    public boolean isMainDriverOn() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.isMainDriverOn();
            }
            return false;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            return xpAudioPolicy.isMainDriverOn();
        }
        return false;
    }

    public boolean isBtHeadPhoneOn() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.isBtHeadPhoneOn();
            }
            return false;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            return xpAudioPolicy.isBtHeadPhoneOn();
        }
        return false;
    }

    public int selectAlarmChannels(int location, int fadeTimeMs, int soundid) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.selectAlarmChannels(location, fadeTimeMs, soundid);
            }
            return -1;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            return xpAudioPolicy.selectAlarmChannels(location, fadeTimeMs, soundid);
        }
        return -1;
    }

    public void checkAlarmVolume() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.checkAlarmVolume();
                return;
            }
            return;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            xpAudioPolicy.checkAlarmVolume();
        }
    }

    public void setBtCallOn(boolean enable) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setBtCallOn(enable);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.setBtCallOn(enable);
        }
    }

    public void setBtCallOnFlag(int flag) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setBtCallOnFlag(flag);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.setBtCallOnFlag(flag);
        }
    }

    public void setNetEcallEnable(boolean enable) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.setNetEcallEnable(enable);
        }
    }

    public int getBtCallOnFlag() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.getBtCallOnFlag();
            }
            return 0;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.getBtCallOnFlag();
        }
        return 0;
    }

    public boolean isBtCallOn() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.isBtCallOn();
            }
            return false;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.isBtCallOn();
        }
        return false;
    }

    public void setBtCallMode(int mode) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setBtCallMode(mode);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.setBtCallMode(mode);
        }
    }

    public int getBtCallMode() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.getBtCallMode();
            }
            return 0;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.getBtCallMode();
        }
        return 0;
    }

    public void setKaraokeOn(boolean on) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setKaraokeOn(on);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.setKaraokeOn(on);
        }
    }

    public boolean isKaraokeOn() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.isKaraokeOn();
            }
            return false;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.isKaraokeOn();
        }
        return false;
    }

    public IKaraoke getXMicService() {
        Log.d(TAG, "getXMicService is ");
        return this.mXMic;
    }

    public boolean isOtherSessionOn() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.isOtherSessionOn();
            }
            return false;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.isOtherSessionOn();
        }
        return false;
    }

    public String getOtherMusicPlayingPkgs() {
        List<String> pkgList = null;
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                pkgList = xuiaudiopolicytrigger.getOtherMusicPlayingPkgs();
            }
        } else {
            xpAudio xpaudio = this.mXpAudio;
            if (xpaudio != null) {
                pkgList = xpaudio.getOtherMusicPlayingPkgs();
            }
        }
        if (pkgList != null && pkgList.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pkgList.size(); i++) {
                sb.append(pkgList.get(i));
                sb.append(";");
            }
            return sb.toString();
        }
        return "";
    }

    public boolean isFmOn() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.isFmOn();
            }
            return false;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.isFmOn();
        }
        return false;
    }

    public void setVoiceStatus(int status) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setVoiceStatus(status);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.setVoiceStatus(status);
        }
    }

    public int getVoiceStatus() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.getVoiceStatus();
            }
            return 0;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.getVoiceStatus();
        }
        return 0;
    }

    public void setVoicePosition(int position, int flag, String pkgName) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setVoicePosition(position, flag, pkgName);
                return;
            }
            return;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            xpAudioPolicy.setVoicePosition(position, flag, pkgName);
        }
    }

    public int getVoicePosition() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.getVoicePosition();
            }
            return 0;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            return xpAudioPolicy.getVoicePosition();
        }
        return 0;
    }

    public boolean setFixedVolume(boolean enable, int vol, int streamType, String callingPackage) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setFixedVolume(enable, vol, streamType, callingPackage);
                return false;
            }
            return false;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.setFixedVolume(enable, vol, streamType, callingPackage);
        }
        return false;
    }

    public boolean isFixedVolume(int streamType) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.isFixedVolume(streamType);
            }
            return false;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.isFixedVolume(streamType);
        }
        return false;
    }

    public void doZenVolumeProcess(boolean in, String callingPackage) {
        int curMusicVol = getStreamVolume(3);
        if (in) {
            if (curMusicVol < 8) {
                this.savedCurVolume = curMusicVol;
                temporaryChangeVolumeDown(3, 8, false, 32, callingPackage);
                this.isZenVolChanged = true;
                return;
            }
            return;
        }
        if (this.isZenVolChanged) {
            temporaryChangeVolumeDown(3, 8, true, 32, callingPackage);
        }
        this.isZenVolChanged = false;
        this.savedCurVolume = -1;
    }

    public boolean isZenVolume() {
        return false;
    }

    public void setVolumeFaded(int StreamType, int vol, int fadetime, String callingPackage) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setVolumeFaded(StreamType, vol, fadetime, callingPackage);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.setVolumeFaded(StreamType, vol, fadetime, callingPackage);
        }
    }

    public void forceChangeToAmpChannel(int channelBits, int activeBits, int volume, boolean stop) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.forceChangeToAmpChannel(channelBits, activeBits, volume, stop);
                return;
            }
            return;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            xpAudioPolicy.forceChangeToAmpChannel(channelBits, activeBits, volume, stop);
        }
    }

    public void restoreMusicVolume(String callingPackage) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.restoreMusicVolume(callingPackage);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.restoreMusicVolume(callingPackage);
        }
    }

    public void playbackControl(int cmd, int param) {
        try {
            if (newPolicyOpen) {
                if (this.mXuiAudioPolicyTrigger != null) {
                    this.mXuiAudioPolicyTrigger.playbackControl(cmd, param);
                }
            } else if (this.mXpAudio != null) {
                this.mXpAudio.playbackControl(cmd, param);
            }
        } catch (Exception e) {
        }
    }

    public void setDangerousTtsStatus(int on) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setDangerousTtsStatus(on);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.setDangerousTtsStatus(on);
        }
    }

    public int getDangerousTtsStatus() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.getDangerousTtsStatus();
            }
            return 0;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            return xpaudio.getDangerousTtsStatus();
        }
        return 0;
    }

    public void setDangerousTtsVolLevel(int level) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.setDangerousTtsVolLevel(level);
                return;
            }
            return;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            xpAudioPolicy.setDangerousTtsVolLevel(level);
        }
    }

    public int getDangerousTtsVolLevel() {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                return xuiaudiopolicytrigger.getDangerousTtsVolLevel();
            }
            return 0;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            return xpAudioPolicy.getDangerousTtsVolLevel();
        }
        return 0;
    }

    public void ChangeChannelByTrack(int usage, int id, boolean start) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.ChangeChannelByTrack(usage, id, start);
                return;
            }
            return;
        }
        XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
        if (xpAudioPolicy != null) {
            xpAudioPolicy.ChangeChannelByTrack(usage, id, start);
        }
    }

    public void temporaryChangeVolumeDown(int StreamType, int dstVol, boolean restoreVol, int flag, String packageName) {
        if (newPolicyOpen) {
            xuiAudioPolicyTrigger xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger;
            if (xuiaudiopolicytrigger != null) {
                xuiaudiopolicytrigger.temporaryChangeVolumeDown(StreamType, dstVol, restoreVol, flag, packageName);
                return;
            }
            return;
        }
        xpAudio xpaudio = this.mXpAudio;
        if (xpaudio != null) {
            xpaudio.temporaryChangeVolumeDown(StreamType, dstVol, restoreVol, flag, packageName);
        }
    }

    public void applyAlarmId(int usage, int id) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.applyAlarmId(usage, id);
        }
    }

    public void setStreamPosition(int streamType, String pkgName, int position, int id) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.setStreamPosition(streamType, pkgName, position, id);
        }
    }

    public void setSoundPositionEnable(boolean enable) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.setSoundPositionEnable(enable);
        }
    }

    public boolean getSoundPositionEnable() {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            return xuiaudiopolicytrigger.getSoundPositionEnable();
        }
        return false;
    }

    public void setMassageSeatLevel(List<String> levelList) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.setMassageSeatLevel(levelList);
        }
    }

    public void setMusicSeatEnable(boolean enable) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.setMusicSeatEnable(enable);
        }
    }

    public boolean getMusicSeatEnable() {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            return xuiaudiopolicytrigger.getMusicSeatEnable();
        }
        return false;
    }

    public void setMusicSeatRythmPause(boolean pause) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.setMusicSeatRythmPause(pause);
        }
    }

    public void setMusicSeatEffect(int index) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.setMusicSeatEffect(index);
        }
    }

    public int getMusicSeatEffect() {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            return xuiaudiopolicytrigger.getMusicSeatEffect();
        }
        return 0;
    }

    public void setMmapToAvasEnable(boolean enable) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.setMmapToAvasEnable(enable);
        }
    }

    public boolean getMmapToAvasEnable() {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            return xuiaudiopolicytrigger.getMmapToAvasEnable();
        }
        return false;
    }

    public void setSpecialOutputId(int outType, int sessionId, boolean enable) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.setSpecialOutputId(outType, sessionId, enable);
        }
    }

    public void setAudioPathWhiteList(int type, String writeList) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.setAudioPathWhiteList(type, writeList);
        }
    }

    public void setSoftTypeVolumeMute(int type, boolean enable) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.setSoftTypeVolumeMute(type, enable);
        }
    }

    public void playAvasSound(int position, String path) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        Log.i(TAG, "playAvasSound  " + position + " " + path);
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.playAvasSound(position, path);
        }
    }

    public void stopAvasSound(String path) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            xuiaudiopolicytrigger.stopAvasSound(path);
        }
    }

    public boolean checkPlayingRouteByPackage(int type, String pkgName) {
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mXuiAudioPolicyTrigger) != null) {
            return xuiaudiopolicytrigger.checkPlayingRouteByPackage(type, pkgName);
        }
        return false;
    }

    public void audioThreadProcess(final int type, final int usage, final int streamType, final int Ppid, final String pkgName) {
        ExecutorService executorService = mFixedThreadExecutor;
        if (executorService != null) {
            executorService.execute(new Runnable() { // from class: com.android.server.audio.AudioService.8
                @Override // java.lang.Runnable
                public void run() {
                    try {
                        Log.d(AudioService.TAG, "audioThreadProcess TYPE:" + type);
                        switch (type) {
                            case 1:
                                if (streamType == 4 || streamType == 5 || streamType == 1 || streamType == 10) {
                                    AudioService.this.setVolumeToHal(streamType);
                                }
                                if (usage != 1 && usage != 4 && usage != 13) {
                                    AudioService.this.applyUsage(usage, Ppid, pkgName);
                                    return;
                                }
                                return;
                            case 2:
                                if ((usage == 4 || usage == 13) && Ppid > 0) {
                                    AudioService.this.applyUsage(usage, Ppid, pkgName);
                                }
                                if (Ppid <= 0) {
                                    Log.i(AudioService.TAG, "baseStart_sysPatch  id:" + Ppid);
                                    return;
                                }
                                return;
                            case 3:
                                if (usage == 11 || usage == 16) {
                                    AudioService.this.ChangeChannelByTrack(usage, Ppid, true);
                                }
                                AudioService.this.setVolumeToHal(streamType);
                                Log.i(AudioService.TAG, "baseStartXui() applyUsage:" + usage + " mPlayerIId:" + Ppid);
                                if (usage != 1) {
                                    AudioService.this.applyUsage(usage, Ppid, pkgName);
                                    return;
                                }
                                return;
                            case 4:
                                if (usage != 1) {
                                    Log.i(AudioService.TAG, "baseApplyUsage() releaseUsage:" + usage + " mPlayerIId:" + Ppid);
                                    AudioService.this.applyUsage(usage, Ppid, pkgName);
                                    return;
                                }
                                return;
                            case 5:
                                if (usage != 1) {
                                    Log.i(AudioService.TAG, "baseReleaseUsage() releaseUsage:" + usage + " Ppid:" + Ppid);
                                    AudioService.this.releaseUsage(usage, Ppid, pkgName);
                                    return;
                                }
                                return;
                            case 6:
                                if (usage != 1) {
                                    if (usage != 13) {
                                        Thread.sleep(100L);
                                    }
                                    Log.i(AudioService.TAG, "basePause() releaseUsage:" + usage + " mPlayerIId:" + Ppid);
                                    AudioService.this.releaseUsage(usage, Ppid, pkgName);
                                    return;
                                }
                                return;
                            case 7:
                                if (usage != 1) {
                                    if (usage == 11 || usage == 16) {
                                        AudioService.this.ChangeChannelByTrack(usage, Ppid, false);
                                    }
                                    if (usage != 13) {
                                        Thread.sleep(100L);
                                    }
                                    Log.i(AudioService.TAG, "baseStop() releaseUsage:" + usage + " mPlayerIId:" + Ppid);
                                    AudioService.this.releaseUsage(usage, Ppid, pkgName);
                                    return;
                                }
                                return;
                            case 8:
                                if (usage != 1) {
                                    if (usage == 11 || usage == 16) {
                                        AudioService.this.ChangeChannelByTrack(usage, Ppid, false);
                                    }
                                    if (usage != 13) {
                                        Thread.sleep(150L);
                                    }
                                    Log.i(AudioService.TAG, "baseRelease() releaseUsage:" + usage + " mPlayerIId:" + Ppid);
                                    AudioService.this.releaseUsage(usage, Ppid, pkgName);
                                    return;
                                }
                                return;
                            default:
                                return;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    private void connectXUI() {
        this.mRemoteConnect = remoteConnect.getInstance(this.mContext);
        this.mRemoteConnect.registerListener(this.mRemoteConnectListerer);
    }

    /* loaded from: classes.dex */
    private class XpAudioEventListener implements XpAudioPolicy.xpAudioEventListener {
        private XpAudioEventListener() {
        }

        @Override // com.xiaopeng.xpaudiopolicy.XpAudioPolicy.xpAudioEventListener
        public void onCommonEvent(int eventType, int eventValue) {
            if (AudioService.mkHandler != null) {
                Message m = AudioService.mkHandler.obtainMessage();
                m.what = eventType;
                m.arg1 = eventValue;
                AudioService.mkHandler.sendMessage(m);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void unregisterListenerLocked(IBinder listenerBinder) {
        Object status = this.mListenersMap.remove(listenerBinder);
        if (status != null) {
            this.mDeathRecipientMap.get(listenerBinder).release();
            this.mDeathRecipientMap.remove(listenerBinder);
        }
    }

    public void registerCallback(String pkgName, IAudioEventListener callBackFunc) {
        Log.i(TAG, "registerCallback " + pkgName);
        if (callBackFunc == null) {
            Log.e(TAG, "registerCallback: Listener is null.");
            throw new IllegalArgumentException("listener cannot be null.");
        }
        if (mkHandler == null) {
            mkHandler = new AudioEventHandler(Looper.getMainLooper());
        }
        if (mXpAudioEventListener == null) {
            mXpAudioEventListener = new XpAudioEventListener();
            XpAudioPolicy xpAudioPolicy = this.mXpAudioPloicy;
            if (xpAudioPolicy != null) {
                xpAudioPolicy.registerAudioListener(mXpAudioEventListener);
            }
        }
        IBinder listenerBinder = callBackFunc.asBinder();
        if (this.mListenersMap.containsKey(listenerBinder)) {
            return;
        }
        AudioDeathRecipient deathRecipient = new AudioDeathRecipient(listenerBinder);
        try {
            listenerBinder.linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to link death for recipient. " + e);
        }
        this.mDeathRecipientMap.put(listenerBinder, deathRecipient);
        this.mListenersMap.isEmpty();
        this.mListenersMap.put(listenerBinder, callBackFunc);
    }

    public void unregisterCallback(String pkgName, IAudioEventListener callBackFunc) {
        if (callBackFunc == null) {
            Log.e(TAG, "unregisterCallback: listener was not registered");
            return;
        }
        IBinder listenerBinder = callBackFunc.asBinder();
        if (!this.mListenersMap.containsKey(listenerBinder)) {
            Log.e(TAG, "unregisterCallback: Listener was not previously registered.");
        }
        unregisterListenerLocked(listenerBinder);
    }

    public void dispatchAudioCallback(int event, int value) {
        if (this.mListenersMap.isEmpty()) {
            Log.e(TAG, "dispatchAudioCallback  ERROR:NO LISTENER");
            return;
        }
        try {
            for (IAudioEventListener listener : this.mListenersMap.values()) {
                listener.AudioEventChangeCallBack(event, value);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class AudioDeathRecipient implements IBinder.DeathRecipient {
        private static final String TAG = "AudioDeathRecipient";
        private IBinder mListenerBinder;

        AudioDeathRecipient(IBinder listenerBinder) {
            this.mListenerBinder = listenerBinder;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            Log.w(TAG, "binderDied " + this.mListenerBinder);
            AudioService.this.unregisterListenerLocked(this.mListenerBinder);
        }

        void release() {
            this.mListenerBinder.unlinkToDeath(this, 0);
        }
    }

    /* loaded from: classes.dex */
    private final class AudioEventHandler extends Handler {
        public AudioEventHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int event = msg.arg1;
            Log.i(AudioService.TAG, "AUDIO_EVENT  handleMessage  :" + msg.what + " " + event);
            AudioService.this.dispatchAudioCallback(msg.what, event);
        }
    }
}
