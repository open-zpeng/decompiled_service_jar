package com.android.server.audio;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IUidObserver;
import android.app.NotificationManager;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
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
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiPlaybackClient;
import android.hardware.hdmi.HdmiTvClient;
import android.media.AudioAttributes;
import android.media.AudioDevicePort;
import android.media.AudioFocusInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioPort;
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
import android.media.MediaPlayer;
import android.media.PlayerBase;
import android.media.SoundEffectParms;
import android.media.SoundField;
import android.media.SoundPool;
import android.media.VolumePolicy;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioPolicyConfig;
import android.media.audiopolicy.IAudioPolicyCallback;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
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
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseIntArray;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.XmlUtils;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.audio.AudioEventLogger;
import com.android.server.audio.AudioServiceEvents;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.UserManagerService;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.utils.PriorityDump;
import com.android.xpeng.audio.xpAudio;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.audio.xpAudioSessionInfo;
import com.xiaopeng.server.app.xpPackageManagerService;
import com.xiaopeng.xpaudiopolicy.XpAudioPolicy;
import com.xiaopeng.xui.xuiaudio.xuiAudioPolicyTrigger;
import com.xiaopeng.xuimanager.IXUIService;
import com.xiaopeng.xuimanager.karaoke.IKaraoke;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.xmlpull.v1.XmlPullParserException;
/* loaded from: classes.dex */
public class AudioService extends IAudioService.Stub implements AccessibilityManager.TouchExplorationStateChangeListener, AccessibilityManager.AccessibilityServicesStateChangeListener {
    private static final String ASSET_FILE_VERSION = "1.0";
    private static final String ATTR_ASSET_FILE = "file";
    private static final String ATTR_ASSET_ID = "id";
    private static final String ATTR_GROUP_NAME = "name";
    private static final String ATTR_VERSION = "version";
    private static final int BTA2DP_DOCK_TIMEOUT_MILLIS = 8000;
    private static final int BT_HEADSET_CNCT_TIMEOUT_MS = 3000;
    private static final int BT_HEARING_AID_GAIN_MIN = -128;
    public static final String CONNECT_INTENT_KEY_ADDRESS = "address";
    public static final String CONNECT_INTENT_KEY_DEVICE_CLASS = "class";
    public static final String CONNECT_INTENT_KEY_HAS_CAPTURE = "hasCapture";
    public static final String CONNECT_INTENT_KEY_HAS_MIDI = "hasMIDI";
    public static final String CONNECT_INTENT_KEY_HAS_PLAYBACK = "hasPlayback";
    public static final String CONNECT_INTENT_KEY_PORT_NAME = "portName";
    public static final String CONNECT_INTENT_KEY_STATE = "state";
    protected static final boolean DEBUG_MODE = true;
    private static final int DEFAULT_STREAM_TYPE_OVERRIDE_DELAY_MS = 0;
    protected static final int DEFAULT_VOL_STREAM_NO_PLAYBACK = 3;
    private static final int DEVICE_MEDIA_UNMUTED_ON_PLUG = 67266444;
    private static final int DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG = 67264524;
    private static final int FLAG_ADJUST_VOLUME = 1;
    private static final String GROUP_TOUCH_SOUNDS = "touch_sounds";
    private static final int INDICATE_SYSTEM_READY_RETRY_DELAY_MS = 1000;
    private static final String KARAOKE_SERVICE = "karaoke";
    static final int LOG_NB_EVENTS_DEVICE_CONNECTION = 30;
    static final int LOG_NB_EVENTS_DYN_POLICY = 10;
    static final int LOG_NB_EVENTS_FORCE_USE = 20;
    static final int LOG_NB_EVENTS_PHONE_STATE = 20;
    static final int LOG_NB_EVENTS_VOLUME = 40;
    private static final int MSG_A2DP_DEVICE_CONFIG_CHANGE = 103;
    private static final int MSG_ACCESSORY_PLUG_MEDIA_UNMUTE = 27;
    private static final int MSG_AUDIO_SERVER_DIED = 4;
    private static final int MSG_BROADCAST_AUDIO_BECOMING_NOISY = 15;
    private static final int MSG_BROADCAST_BT_CONNECTION_STATE = 19;
    private static final int MSG_BTA2DP_DOCK_TIMEOUT = 106;
    private static final int MSG_BT_HEADSET_CNCT_FAILED = 9;
    private static final int MSG_CHECK_MUSIC_ACTIVE = 14;
    private static final int MSG_CONFIGURE_SAFE_MEDIA_VOLUME = 16;
    private static final int MSG_CONFIGURE_SAFE_MEDIA_VOLUME_FORCED = 17;
    private static final int MSG_DISABLE_AUDIO_FOR_UID = 104;
    private static final int MSG_DISPATCH_AUDIO_SERVER_STATE = 29;
    private static final int MSG_DYN_POLICY_MIX_STATE_UPDATE = 25;
    private static final int MSG_ENABLE_SURROUND_FORMATS = 30;
    private static final int MSG_INDICATE_SYSTEM_READY = 26;
    private static final int MSG_LOAD_SOUND_EFFECTS = 7;
    private static final int MSG_NOTIFY_VOL_EVENT = 28;
    private static final int MSG_PERSIST_MUSIC_ACTIVE_MS = 22;
    private static final int MSG_PERSIST_RINGER_MODE = 3;
    private static final int MSG_PERSIST_SAFE_VOLUME_STATE = 18;
    private static final int MSG_PERSIST_VOLUME = 1;
    private static final int MSG_PLAY_SOUND_EFFECT = 5;
    private static final int MSG_REPORT_NEW_ROUTES = 12;
    private static final int MSG_SET_A2DP_SINK_CONNECTION_STATE = 102;
    private static final int MSG_SET_A2DP_SRC_CONNECTION_STATE = 101;
    private static final int MSG_SET_ALL_VOLUMES = 10;
    private static final int MSG_SET_DEVICE_VOLUME = 0;
    private static final int MSG_SET_FORCE_BT_A2DP_USE = 13;
    private static final int MSG_SET_FORCE_USE = 8;
    private static final int MSG_SET_HEARING_AID_CONNECTION_STATE = 105;
    private static final int MSG_SET_WIRED_DEVICE_CONNECTION_STATE = 100;
    private static final int MSG_SYSTEM_READY = 21;
    private static final int MSG_TEST_GET_SOUND_EFFECT_PARMS = 204;
    private static final int MSG_TEST_GET_SOUND_FIELD = 202;
    private static final int MSG_TEST_SET_SOUND_EFFECT_MODE = 203;
    private static final int MSG_TEST_SET_SOUND_FIELD = 201;
    private static final int MSG_UNLOAD_SOUND_EFFECTS = 20;
    private static final int MSG_UNMUTE_STREAM = 24;
    private static final int MUSIC_ACTIVE_POLL_PERIOD_MS = 60000;
    private static final int NUM_SOUNDPOOL_CHANNELS = 4;
    private static final int PERSIST_DELAY = 500;
    private static final String RECORDSTATE_ACTIOIN_PKG = "xiaopeng.record.using.PKGNAME";
    private static final String RECORDSTATE_ACTIOIN_STATUS = "xiaopeng.record.using.STATUS";
    private static final int SAFE_MEDIA_VOLUME_ACTIVE = 3;
    private static final int SAFE_MEDIA_VOLUME_DISABLED = 1;
    private static final int SAFE_MEDIA_VOLUME_INACTIVE = 2;
    private static final int SAFE_MEDIA_VOLUME_NOT_CONFIGURED = 0;
    private static final int SAFE_VOLUME_CONFIGURE_TIMEOUT_MS = 30000;
    private static final int SCO_MODE_MAX = 2;
    private static final int SCO_MODE_RAW = 1;
    private static final int SCO_MODE_UNDEFINED = -1;
    private static final int SCO_MODE_VIRTUAL_CALL = 0;
    private static final int SCO_MODE_VR = 2;
    private static final int SCO_STATE_ACTIVATE_REQ = 1;
    private static final int SCO_STATE_ACTIVE_EXTERNAL = 2;
    private static final int SCO_STATE_ACTIVE_INTERNAL = 3;
    private static final int SCO_STATE_DEACTIVATE_REQ = 4;
    private static final int SCO_STATE_DEACTIVATING = 5;
    private static final int SCO_STATE_INACTIVE = 0;
    private static final int SENDMSG_NOOP = 1;
    private static final int SENDMSG_QUEUE = 2;
    private static final int SENDMSG_REPLACE = 0;
    private static final int SOUND_EFFECTS_LOAD_TIMEOUT_MS = 5000;
    private static final String SOUND_EFFECTS_PATH = "/media/audio/ui/";
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_DISCONNECTED = 0;
    private static final String SYSTEMUI_PACKAGE_NAME = "com.android.systemui";
    private static final String SYSTEM_MIC_MUTE = "persist.sys.mic.mute";
    private static final String SYSTEM_SOUND_CONFIG = "persist.audio.system_sound";
    private static final String TAG = "AudioService";
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
    private BluetoothA2dp mA2dp;
    private int[] mAccessibilityServiceUids;
    private final AppOpsManager mAppOps;
    private PowerManager.WakeLock mAudioEventWakeLock;
    private AudioHandler mAudioHandler;
    private AudioSystemThread mAudioSystemThread;
    private boolean mBluetoothA2dpEnabled;
    private BluetoothHeadset mBluetoothHeadset;
    private BluetoothDevice mBluetoothHeadsetDevice;
    @GuardedBy("mSettingsLock")
    private boolean mCameraSoundForced;
    private int mConnectionState;
    private final ContentResolver mContentResolver;
    private final Context mContext;
    private String mDockAddress;
    private String mEnabledSurroundFormats;
    private int mEncodedSurroundMode;
    private IAudioPolicyCallback mExtVolumeController;
    private int mForcedUseForComm;
    private int mForcedUseForCommExt;
    private final boolean mHasVibrator;
    private boolean mHdmiCecSink;
    private HdmiControlManager mHdmiManager;
    private HdmiPlaybackClient mHdmiPlaybackClient;
    private HdmiTvClient mHdmiTvClient;
    private BluetoothHearingAid mHearingAid;
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
    private final RecordingActivityMonitor mRecordMonitor;
    private int mRingerAndZenModeMutedStreams;
    @GuardedBy("mSettingsLock")
    private int mRingerMode;
    private AudioManagerInternal.RingerModeDelegate mRingerModeDelegate;
    private volatile IRingtonePlayer mRingtonePlayer;
    private int mSafeMediaVolumeIndex;
    private Integer mSafeMediaVolumeState;
    private float mSafeUsbMediaVolumeDbfs;
    private int mSafeUsbMediaVolumeIndex;
    private int mScoAudioMode;
    private int mScoAudioState;
    private int mScoConnectionState;
    private SettingsObserver mSettingsObserver;
    private SoundPool mSoundPool;
    private SoundPoolCallback mSoundPoolCallBack;
    private SoundPoolListenerThread mSoundPoolListenerThread;
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
    protected static final boolean DEBUG_AP = Log.isLoggable("AudioService.AP", 3);
    protected static final boolean DEBUG_VOL = Log.isLoggable("AudioService.VOL", 3);
    protected static final boolean DEBUG_DEVICES = Log.isLoggable("AudioService.DEVICES", 3);
    private static final int mXuiVolPolicy = SystemProperties.getInt("persist.adjustvolume.policy", 2);
    private static int mCurActiveStream = -1;
    private static boolean mActiveStreamStatus = false;
    private static ExecutorService mFixedThreadExecutor = null;
    private static final boolean newPolicyOpen = AudioManager.newPolicyOpen;
    private static final List<String> SOUND_EFFECT_FILES = new ArrayList();
    protected static int[] MAX_STREAM_VOLUME = {5, 7, 7, 15, 7, 7, 15, 7, 15, 15, 15};
    protected static int[] MIN_STREAM_VOLUME = {1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1};
    private static final int[] STREAM_VOLUME_OPS = {34, 36, 35, 36, 37, 38, 39, 36, 36, 36, 64};
    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder().setContentType(4).setUsage(13).build();
    private static Long mLastDeviceConnectMsgTime = new Long(0);
    private static boolean sIndependentA11yVolume = false;
    private static final String[] RINGER_MODE_NAMES = {"SILENT", "VIBRATE", PriorityDump.PRIORITY_ARG_NORMAL};
    private int savedCurVolume = -1;
    private boolean isZenVolChanged = false;
    private final boolean SYSTEM_SOUND_DEFAULT = true;
    private final Object mSystemSoundLock = new Object();
    private final ServiceConnection mXMicConnectionListener = new ServiceConnection() { // from class: com.android.server.audio.AudioService.1
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
    private final VolumeController mVolumeController = new VolumeController();
    private int mMode = 0;
    private final Object mSettingsLock = new Object();
    private final Object mSoundEffectsLock = new Object();
    private final int[][] SOUND_EFFECT_FILES_MAP = (int[][]) Array.newInstance(int.class, 18, 2);
    private final int[] STREAM_VOLUME_ALIAS_VOICE = {0, 2, 2, 3, 4, 2, 6, 2, 2, 3, 3};
    private final int[] STREAM_VOLUME_ALIAS_TELEVISION = {3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3};
    private final int[] STREAM_VOLUME_ALIAS_DEFAULT = {0, 2, 2, 3, 4, 2, 6, 2, 2, 3, 3};
    private final AudioSystem.ErrorCallback mAudioSystemCallback = new AudioSystem.ErrorCallback() { // from class: com.android.server.audio.AudioService.2
        public void onError(int error) {
            if (error == 100 && AudioService.this.mAudioHandler != null) {
                AudioService.sendMsg(AudioService.this.mAudioHandler, 4, 1, 0, 0, null, 0);
                AudioService.sendMsg(AudioService.this.mAudioHandler, 29, 2, 0, 0, null, 0);
            }
        }
    };
    @GuardedBy("mSettingsLock")
    private int mRingerModeExternal = -1;
    private int mRingerModeAffectedStreams = 0;
    private int mZenModeAffectedStreams = 0;
    private final BroadcastReceiver mReceiver = new AudioServiceBroadcastReceiver();
    private final UserManagerInternal.UserRestrictionsListener mUserRestrictionsListener = new AudioServiceUserRestrictionsListener();
    private final ArrayMap<String, DeviceListSpec> mConnectedDevices = new ArrayMap<>();
    private final ArrayList<SetModeDeathHandler> mSetModeDeathHandlers = new ArrayList<>();
    private final ArrayList<ScoClient> mScoClients = new ArrayList<>();
    private Looper mSoundPoolLooper = null;
    private int mPrevVolDirection = 0;
    private int mVolumeControlStream = -1;
    private boolean mUserSelectedVolumeControlStream = false;
    private final Object mForceControlStreamLock = new Object();
    private ForceControlStreamClient mForceControlStreamClient = null;
    private final Object mBluetoothA2dpEnabledLock = new Object();
    final AudioRoutesInfo mCurAudioRoutes = new AudioRoutesInfo();
    final RemoteCallbackList<IAudioRoutesObserver> mRoutesObservers = new RemoteCallbackList<>();
    int mFixedVolumeDevices = 2890752;
    int mFullVolumeDevices = 0;
    private boolean mDockAudioMediaEnabled = true;
    private int mDockState = 0;
    private final Object mHearingAidLock = new Object();
    private final Object mA2dpAvrcpLock = new Object();
    private boolean mAvrcpAbsVolSupported = false;
    private VolumePolicy mVolumePolicy = VolumePolicy.DEFAULT;
    private final Object mAccessibilityServiceUidsLock = new Object();
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
            AudioService.this.queueMsgUnderWakeLock(AudioService.this.mAudioHandler, 104, disable ? 1 : 0, uid, null, 0);
        }
    };
    private final BroadcastReceiver mBootReceiver = new BroadcastReceiver() { // from class: com.android.server.audio.AudioService.4
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                String action = intent.getAction();
                if (action.equals("android.intent.action.LOCKED_BOOT_COMPLETED")) {
                    Log.d(AudioService.TAG, "ACTION_LOCKED_BOOT_COMPLETED");
                    if (AudioService.this.mXUIService == null) {
                        AudioService.this.BindXUIService();
                    }
                }
            }
        }
    };
    private int mRmtSbmxFullVolRefCount = 0;
    private ArrayList<RmtSbmxFullVolDeathHandler> mRmtSbmxFullVolDeathHandlers = new ArrayList<>();
    private BluetoothProfile.ServiceListener mBluetoothProfileServiceListener = new BluetoothProfile.ServiceListener() { // from class: com.android.server.audio.AudioService.5
        /* JADX WARN: Code restructure failed: missing block: B:51:0x013a, code lost:
            r17.this$0.mScoAudioState = 0;
            r17.this$0.broadcastScoConnectionState(0);
         */
        @Override // android.bluetooth.BluetoothProfile.ServiceListener
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public void onServiceConnected(int r18, android.bluetooth.BluetoothProfile r19) {
            /*
                Method dump skipped, instructions count: 484
                To view this dump add '--comments-level debug' option
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.audio.AudioService.AnonymousClass5.onServiceConnected(int, android.bluetooth.BluetoothProfile):void");
        }

        @Override // android.bluetooth.BluetoothProfile.ServiceListener
        public void onServiceDisconnected(int profile) {
            if (profile == 11) {
                AudioService.this.disconnectA2dpSink();
            } else if (profile != 21) {
                switch (profile) {
                    case 1:
                        AudioService.this.disconnectHeadset();
                        return;
                    case 2:
                        AudioService.this.disconnectA2dp();
                        return;
                    default:
                        return;
                }
            } else {
                AudioService.this.disconnectHearingAid();
            }
        }
    };
    int mBecomingNoisyIntentDevices = 201490316;
    private int mMcc = 0;
    private final int mSafeMediaVolumeDevices = 67108876;
    private boolean mHdmiSystemAudioSupported = false;
    private MyDisplayStatusCallback mHdmiDisplayStatusCallback = new MyDisplayStatusCallback();
    private final AudioEventLogger mModeLogger = new AudioEventLogger(20, "phone state (logged after successfull call to AudioSystem.setPhoneState(int))");
    private final AudioEventLogger mDeviceLogger = new AudioEventLogger(30, "wired/A2DP device connection");
    private final AudioEventLogger mForceUseLogger = new AudioEventLogger(20, "force use (logged before setForceUse() is executed)");
    private final AudioEventLogger mVolumeLogger = new AudioEventLogger(40, "volume changes (logged when command received by AudioService)");
    private final AudioEventLogger mDynPolicyLogger = new AudioEventLogger(10, "dynamic policy events (logged when command received by AudioService)");
    private final Object mExtVolumeControllerLock = new Object();
    private final AudioSystem.DynamicPolicyCallback mDynPolicyCallback = new AudioSystem.DynamicPolicyCallback() { // from class: com.android.server.audio.AudioService.7
        public void onDynamicPolicyMixStateUpdate(String regId, int state) {
            if (!TextUtils.isEmpty(regId)) {
                AudioService.sendMsg(AudioService.this.mAudioHandler, 25, 2, state, 0, regId, 0);
            }
        }
    };
    private HashMap<IBinder, AsdProxy> mAudioServerStateListeners = new HashMap<>();
    private final Map<IBinder, IAudioEventListener> mListenersMap = new HashMap();
    private final Map<IBinder, AudioDeathRecipient> mDeathRecipientMap = new HashMap();
    IAudioEventListener iAudioListener = new IAudioEventListener.Stub() { // from class: com.android.server.audio.AudioService.9
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
    private final String RECORDSTATE_ACTIOIN = "xiaopeng.record.using.Action";
    private final HashMap<IBinder, AudioPolicyProxy> mAudioPolicies = new HashMap<>();
    @GuardedBy("mAudioPolicies")
    private int mAudioPolicyCounter = 0;
    private final UserManagerInternal mUserManagerInternal = (UserManagerInternal) LocalServices.getService(UserManagerInternal.class);
    private final ActivityManagerInternal mActivityManagerInternal = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);

    static /* synthetic */ int access$12208(AudioService x0) {
        int i = x0.mAudioPolicyCounter;
        x0.mAudioPolicyCounter = i + 1;
        return i;
    }

    public IKaraoke getXMicService() {
        Log.d(TAG, "getXMicService is ");
        return this.mXMic;
    }

    private boolean isPlatformVoice() {
        return this.mPlatformType == 1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isPlatformTelevision() {
        return this.mPlatformType == 2;
    }

    private boolean isPlatformAutomotive() {
        return this.mContext.getPackageManager().hasSystemFeature("android.hardware.type.automotive");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class DeviceListSpec {
        String mDeviceAddress;
        String mDeviceName;
        int mDeviceType;

        public DeviceListSpec(int deviceType, String deviceName, String deviceAddress) {
            this.mDeviceType = deviceType;
            this.mDeviceName = deviceName;
            this.mDeviceAddress = deviceAddress;
        }

        public String toString() {
            return "[type:0x" + Integer.toHexString(this.mDeviceType) + " name:" + this.mDeviceName + " address:" + this.mDeviceAddress + "]";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String makeDeviceListKey(int device, String deviceAddress) {
        return "0x" + Integer.toHexString(device) + ":" + deviceAddress;
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
        this.mSystemSoundOn = true;
        this.mContext = context;
        this.mContentResolver = context.getContentResolver();
        this.mAppOps = (AppOpsManager) context.getSystemService("appops");
        this.mPlatformType = AudioSystem.getPlatformType(context);
        this.mIsSingleVolume = AudioSystem.isSingleVolume(context);
        PowerManager pm = (PowerManager) context.getSystemService("power");
        this.mAudioEventWakeLock = pm.newWakeLock(1, "handleAudioEvent");
        this.mVibrator = (Vibrator) context.getSystemService("vibrator");
        this.mHasVibrator = this.mVibrator == null ? false : this.mVibrator.hasVibrator();
        int maxCallVolume = SystemProperties.getInt("ro.config.vc_call_vol_steps", -1);
        if (maxCallVolume != -1) {
            MAX_STREAM_VOLUME[0] = maxCallVolume;
            AudioSystem.DEFAULT_STREAM_VOLUME[0] = (maxCallVolume * 3) / 4;
        }
        int maxMusicVolume = SystemProperties.getInt("ro.config.media_vol_steps", -1);
        if (maxMusicVolume != -1) {
            MAX_STREAM_VOLUME[3] = maxMusicVolume;
        }
        int defaultMusicVolume = SystemProperties.getInt("ro.config.media_vol_default", -1);
        if (defaultMusicVolume != -1 && defaultMusicVolume <= MAX_STREAM_VOLUME[3]) {
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
            AudioSystem.DEFAULT_STREAM_VOLUME[4] = (6 * MAX_STREAM_VOLUME[4]) / 7;
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
        sSoundEffectVolumeDb = context.getResources().getInteger(17694867);
        this.mForcedUseForComm = 0;
        createAudioSystemThread();
        AudioSystem.setErrorCallback(this.mAudioSystemCallback);
        boolean cameraSoundForced = readCameraSoundForced();
        this.mCameraSoundForced = new Boolean(cameraSoundForced).booleanValue();
        sendMsg(this.mAudioHandler, 8, 2, 4, cameraSoundForced ? 11 : 0, new String("AudioService ctor"), 0);
        this.mSafeMediaVolumeState = new Integer(Settings.Global.getInt(this.mContentResolver, "audio_safe_volume_state", 0));
        this.mSafeMediaVolumeIndex = this.mContext.getResources().getInteger(17694852) * 10;
        this.mUseFixedVolume = this.mContext.getResources().getBoolean(17957067);
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
        this.mSystemSoundOn = SystemProperties.getBoolean(SYSTEM_SOUND_CONFIG, true);
        mFixedThreadExecutor = Executors.newFixedThreadPool(10);
        IntentFilter mIntentFilter = new IntentFilter();
        mIntentFilter.addAction("android.intent.action.LOCKED_BOOT_COMPLETED");
        this.mContext.registerReceiver(this.mBootReceiver, mIntentFilter);
    }

    public void systemReady() {
        sendMsg(this.mAudioHandler, 21, 2, 0, 0, null, 0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void BindXUIService() {
        if (this.mXUIService == null && this.mContext != null) {
            Intent intent = new Intent();
            intent.setPackage(XUI_SERVICE_PACKAGE);
            intent.setAction(XUI_SERVICE_INTERFACE_NAME);
            Log.i(TAG, "systemReady() bind XUIService");
            this.mContext.bindService(intent, this.mXMicConnectionListener, 1);
        }
    }

    public void onSystemReady() {
        this.mSystemReady = true;
        sendMsg(this.mAudioHandler, 7, 2, 0, 0, null, 0);
        this.mScoConnectionState = -1;
        resetBluetoothSco();
        getBluetoothHeadset();
        Intent newIntent = new Intent("android.media.SCO_AUDIO_STATE_CHANGED");
        newIntent.putExtra("android.media.extra.SCO_AUDIO_STATE", 0);
        sendStickyBroadcastToAll(newIntent);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            adapter.getProfileProxy(this.mContext, this.mBluetoothProfileServiceListener, 2);
            adapter.getProfileProxy(this.mContext, this.mBluetoothProfileServiceListener, 21);
        }
        if (this.mContext.getPackageManager().hasSystemFeature("android.hardware.hdmi.cec")) {
            this.mHdmiManager = (HdmiControlManager) this.mContext.getSystemService(HdmiControlManager.class);
            synchronized (this.mHdmiManager) {
                this.mHdmiTvClient = this.mHdmiManager.getTvClient();
                if (this.mHdmiTvClient != null) {
                    this.mFixedVolumeDevices &= -2883587;
                }
                this.mHdmiPlaybackClient = this.mHdmiManager.getPlaybackClient();
                this.mHdmiCecSink = false;
            }
        }
        this.mNm = (NotificationManager) this.mContext.getSystemService("notification");
        sendMsg(this.mAudioHandler, 17, 0, 0, 0, TAG, SystemProperties.getBoolean("audio.safemedia.bypass", false) ? 0 : SAFE_VOLUME_CONFIGURE_TIMEOUT_MS);
        initA11yMonitoring();
        onIndicateSystemReady();
        if (newPolicyOpen) {
            this.mXuiAudioPolicyTrigger = new xuiAudioPolicyTrigger(this.mContext);
            this.mMediaFocusControl.setXuiAudio(this.mXuiAudioPolicyTrigger);
            return;
        }
        this.mXpAudio = new xpAudio(this.mContext);
        this.mMediaFocusControl.setXpAudio(this.mXpAudio);
        this.mXpAudioPloicy = XpAudioPolicy.getInstance();
    }

    public IXpVehicle getXpVehicle() {
        Log.d(TAG, "getXpVehicle()");
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.getXpVehicle();
            }
            return null;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.getXpVehicle();
        } else {
            return null;
        }
    }

    void onIndicateSystemReady() {
        if (AudioSystem.systemReady() == 0) {
            return;
        }
        sendMsg(this.mAudioHandler, 26, 0, 0, 0, null, 1000);
    }

    public void onAudioServerDied() {
        int forDock;
        int forSys;
        int i;
        if (!this.mSystemReady || AudioSystem.checkAudioFlinger() != 0) {
            Log.e(TAG, "Audioserver died.");
            sendMsg(this.mAudioHandler, 4, 1, 0, 0, null, 500);
            return;
        }
        Log.e(TAG, "Audioserver started.");
        AudioSystem.setParameters("restarting=true");
        readAndSetLowRamDevice();
        synchronized (this.mConnectedDevices) {
            forDock = 0;
            for (int i2 = 0; i2 < this.mConnectedDevices.size(); i2++) {
                DeviceListSpec spec = this.mConnectedDevices.valueAt(i2);
                AudioSystem.setDeviceConnectionState(spec.mDeviceType, 1, spec.mDeviceAddress, spec.mDeviceName);
            }
        }
        if (AudioSystem.setPhoneState(this.mMode) == 0) {
            this.mModeLogger.log(new AudioEventLogger.StringEvent("onAudioServerDied causes setPhoneState(" + AudioSystem.modeToString(this.mMode) + ")"));
        }
        this.mForceUseLogger.log(new AudioServiceEvents.ForceUseEvent(0, this.mForcedUseForComm, "onAudioServerDied"));
        AudioSystem.setForceUse(0, this.mForcedUseForComm);
        this.mForceUseLogger.log(new AudioServiceEvents.ForceUseEvent(2, this.mForcedUseForComm, "onAudioServerDied"));
        AudioSystem.setForceUse(2, this.mForcedUseForComm);
        synchronized (this.mSettingsLock) {
            if (!this.mCameraSoundForced) {
                forSys = 0;
            } else {
                forSys = 11;
            }
        }
        this.mForceUseLogger.log(new AudioServiceEvents.ForceUseEvent(4, forSys, "onAudioServerDied"));
        AudioSystem.setForceUse(4, forSys);
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        int streamType = numStreamTypes - 1;
        while (true) {
            i = 10;
            if (streamType < 0) {
                break;
            }
            VolumeStreamState streamState = this.mStreamStates[streamType];
            AudioSystem.initStreamVolume(streamType, streamState.mIndexMin / 10, streamState.mIndexMax / 10);
            streamState.applyAllVolumes();
            streamType--;
        }
        updateMasterMono(this.mContentResolver);
        setRingerModeInt(getRingerModeInternal(), false);
        if (this.mMonitorRotation) {
            RotationHelper.updateOrientation();
        }
        synchronized (this.mBluetoothA2dpEnabledLock) {
            if (this.mBluetoothA2dpEnabled) {
                i = 0;
            }
            int forMed = i;
            this.mForceUseLogger.log(new AudioServiceEvents.ForceUseEvent(1, forMed, "onAudioServerDied"));
            AudioSystem.setForceUse(1, forMed);
        }
        synchronized (this.mSettingsLock) {
            if (this.mDockAudioMediaEnabled) {
                forDock = 8;
            }
            this.mForceUseLogger.log(new AudioServiceEvents.ForceUseEvent(3, forDock, "onAudioServerDied"));
            AudioSystem.setForceUse(3, forDock);
            sendEncodedSurroundMode(this.mContentResolver, "onAudioServerDied");
            sendEnabledSurroundFormats(this.mContentResolver, true);
        }
        if (this.mHdmiManager != null) {
            synchronized (this.mHdmiManager) {
                if (this.mHdmiTvClient != null) {
                    setHdmiSystemAudioSupported(this.mHdmiSystemAudioSupported);
                }
            }
        }
        synchronized (this.mAudioPolicies) {
            for (AudioPolicyProxy policy : this.mAudioPolicies.values()) {
                policy.connectMixes();
            }
        }
        onIndicateSystemReady();
        AudioSystem.setParameters("restarting=false");
        sendMsg(this.mAudioHandler, 29, 2, 1, 0, null, 0);
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

    /* JADX INFO: Access modifiers changed from: private */
    public void checkAllFixedVolumeDevices() {
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int streamType = 0; streamType < numStreamTypes; streamType++) {
            this.mStreamStates[streamType].checkFixedVolumeDevices();
        }
    }

    private void checkAllFixedVolumeDevices(int streamType) {
        this.mStreamStates[streamType].checkFixedVolumeDevices();
    }

    private void checkMuteAffectedStreams() {
        for (int i = 0; i < this.mStreamStates.length; i++) {
            VolumeStreamState vss = this.mStreamStates[i];
            if (vss.mIndexMin > 0 && vss.mStreamType != 0) {
                this.mMuteAffectedStreams &= ~(1 << vss.mStreamType);
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
                AudioSystem.DEFAULT_STREAM_VOLUME[stream] = rescaleIndex(AudioSystem.DEFAULT_STREAM_VOLUME[mStreamVolumeAlias[stream]], mStreamVolumeAlias[stream], stream);
            }
        }
    }

    private void dumpStreamStates(PrintWriter pw) {
        pw.println("\nStream volumes (device: index)");
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int i = 0; i < numStreamTypes; i++) {
            pw.println("- " + AudioSystem.STREAM_NAMES[i] + ":");
            this.mStreamStates[i].dump(pw);
            pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        }
        pw.print("\n- mute affected streams = 0x");
        pw.println(Integer.toHexString(this.mMuteAffectedStreams));
    }

    private void updateStreamVolumeAlias(boolean updateVolumes, String caller) {
        int dtmfStreamAlias = 3;
        int a11yStreamAlias = sIndependentA11yVolume ? 10 : 3;
        if (this.mIsSingleVolume) {
            mStreamVolumeAlias = this.STREAM_VOLUME_ALIAS_TELEVISION;
            dtmfStreamAlias = 3;
        } else if (this.mPlatformType == 1) {
            mStreamVolumeAlias = this.STREAM_VOLUME_ALIAS_VOICE;
            dtmfStreamAlias = 2;
        } else {
            mStreamVolumeAlias = this.STREAM_VOLUME_ALIAS_DEFAULT;
        }
        int dtmfStreamAlias2 = dtmfStreamAlias;
        if (this.mIsSingleVolume) {
            this.mRingerModeAffectedStreams = 0;
        } else if (isInCommunication()) {
            dtmfStreamAlias2 = 0;
            this.mRingerModeAffectedStreams &= -257;
        } else {
            this.mRingerModeAffectedStreams |= 256;
        }
        int dtmfStreamAlias3 = dtmfStreamAlias2;
        mStreamVolumeAlias[8] = dtmfStreamAlias3;
        mStreamVolumeAlias[10] = a11yStreamAlias;
        if (updateVolumes && this.mStreamStates != null) {
            updateDefaultVolumes();
            synchronized (this.mSettingsLock) {
                synchronized (VolumeStreamState.class) {
                    this.mStreamStates[8].setAllIndexes(this.mStreamStates[dtmfStreamAlias3], caller);
                    this.mStreamStates[10].mVolumeIndexSettingName = Settings.System.VOLUME_SETTINGS_INT[a11yStreamAlias];
                    this.mStreamStates[10].setAllIndexes(this.mStreamStates[a11yStreamAlias], caller);
                    this.mStreamStates[10].refreshRange(mStreamVolumeAlias[10]);
                }
            }
            if (sIndependentA11yVolume) {
                this.mStreamStates[10].readSettings();
            }
            setRingerModeInt(getRingerModeInternal(), false);
            sendMsg(this.mAudioHandler, 10, 2, 0, 0, this.mStreamStates[8], 0);
            sendMsg(this.mAudioHandler, 10, 2, 0, 0, this.mStreamStates[10], 0);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void readDockAudioSettings(ContentResolver cr) {
        this.mDockAudioMediaEnabled = Settings.Global.getInt(cr, "dock_audio_media_enabled", 0) == 1;
        sendMsg(this.mAudioHandler, 8, 2, 3, this.mDockAudioMediaEnabled ? 8 : 0, new String("readDockAudioSettings"), 0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateMasterMono(ContentResolver cr) {
        boolean masterMono = Settings.System.getIntForUser(cr, "master_mono", 0, -2) == 1;
        if (DEBUG_VOL) {
            Log.d(TAG, String.format("Master mono %b", Boolean.valueOf(masterMono)));
        }
        AudioSystem.setMasterMono(masterMono);
    }

    private void sendEncodedSurroundMode(ContentResolver cr, String eventSource) {
        int encodedSurroundMode = Settings.Global.getInt(cr, "encoded_surround_output", 0);
        sendEncodedSurroundMode(encodedSurroundMode, eventSource);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendEncodedSurroundMode(int encodedSurroundMode, String eventSource) {
        int forceSetting = 16;
        switch (encodedSurroundMode) {
            case 0:
                forceSetting = 0;
                break;
            case 1:
                forceSetting = 13;
                break;
            case 2:
                forceSetting = 14;
                break;
            case 3:
                forceSetting = 15;
                break;
            default:
                Log.e(TAG, "updateSurroundSoundSettings: illegal value " + encodedSurroundMode);
                break;
        }
        if (forceSetting != 16) {
            sendMsg(this.mAudioHandler, 8, 2, 6, forceSetting, eventSource, 0);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendEnabledSurroundFormats(ContentResolver cr, boolean forceUpdate) {
        if (this.mEncodedSurroundMode != 3) {
            return;
        }
        String enabledSurroundFormats = Settings.Global.getString(cr, "encoded_surround_output_enabled_formats");
        if (enabledSurroundFormats == null) {
            enabledSurroundFormats = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
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
        sendMsg(this.mAudioHandler, 30, 2, 0, 0, formats, 0);
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

    private void readPersistedSettings() {
        ContentResolver cr = this.mContentResolver;
        int i = 2;
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
            this.mVibrateSetting = AudioSystem.getValueForVibrateSetting(0, 1, this.mHasVibrator ? 2 : 0);
            int i2 = this.mVibrateSetting;
            if (!this.mHasVibrator) {
                i = 0;
            }
            this.mVibrateSetting = AudioSystem.getValueForVibrateSetting(i2, 0, i);
            updateRingerAndZenModeAffectedStreams();
            readDockAudioSettings(cr);
            sendEncodedSurroundMode(cr, "readPersistedSettings");
            sendEnabledSurroundFormats(cr, true);
        }
        this.mMuteAffectedStreams = Settings.System.getIntForUser(cr, "mute_streams_affected", 47, -2);
        updateMasterMono(cr);
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
        if (DEBUG_VOL) {
            Log.d(TAG, String.format("Master mute %s, user=%d", Boolean.valueOf(masterMute), Integer.valueOf(currentUser)));
        }
        setSystemAudioMute(masterMute);
        AudioSystem.setMasterMute(masterMute);
        broadcastMasterMuteStatus(masterMute);
        AudioSystem.muteMicrophone(SystemProperties.getBoolean(SYSTEM_MIC_MUTE, false));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int rescaleIndex(int index, int srcStream, int dstStream) {
        int rescaled = ((this.mStreamStates[dstStream].getMaxIndex() * index) + (this.mStreamStates[srcStream].getMaxIndex() / 2)) / this.mStreamStates[srcStream].getMaxIndex();
        if (rescaled < this.mStreamStates[dstStream].getMinIndex()) {
            return this.mStreamStates[dstStream].getMinIndex();
        }
        return rescaled;
    }

    private boolean allowAdjustVolume(String callingPackage) {
        int uid = Binder.getCallingUid();
        if (uid == 1000 || uid == 10000 || "com.android.systemui".equals(callingPackage)) {
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
        if (!this.mUseFixedVolume && extVolCtlr != null) {
            sendMsg(this.mAudioHandler, 28, 2, direction, 0, extVolCtlr, 0);
        } else {
            adjustSuggestedStreamVolume(direction, suggestedStreamType, flags, callingPackage, caller, Binder.getCallingUid());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags, String callingPackage, String caller, int uid) {
        boolean activeForReal;
        int streamType;
        int maybeActiveStreamType;
        int direction2;
        int flags2;
        int flags3 = flags;
        if (DEBUG_VOL) {
            Log.d(TAG, "adjustSuggestedStreamVolume() stream=" + suggestedStreamType + ", flags=" + flags3 + ", caller=" + caller + ", volControlStream=" + this.mVolumeControlStream + ", userSelect=" + this.mUserSelectedVolumeControlStream);
        }
        this.mVolumeLogger.log(new AudioServiceEvents.VolumeEvent(0, suggestedStreamType, direction, flags3, callingPackage + SliceClientPermissions.SliceAuthority.DELIMITER + caller + " uid:" + uid));
        synchronized (this.mForceControlStreamLock) {
            if (this.mUserSelectedVolumeControlStream) {
                int streamType2 = this.mVolumeControlStream;
                streamType = streamType2;
            } else {
                int maybeActiveStreamType2 = getActiveStreamType(suggestedStreamType);
                if (maybeActiveStreamType2 != 2 && maybeActiveStreamType2 != 5) {
                    activeForReal = AudioSystem.isStreamActive(maybeActiveStreamType2, 0);
                    Log.d(TAG, "adjustSuggestedStreamVolume  activeForReal:" + activeForReal + "  maybeActiveStreamType:" + maybeActiveStreamType2 + " mVolumeControlStream:" + this.mVolumeControlStream);
                    if (!activeForReal && this.mVolumeControlStream != -1) {
                        streamType = this.mVolumeControlStream;
                    }
                    streamType = maybeActiveStreamType2;
                }
                activeForReal = wasStreamActiveRecently(maybeActiveStreamType2, 0);
                Log.d(TAG, "adjustSuggestedStreamVolume  activeForReal:" + activeForReal + "  maybeActiveStreamType:" + maybeActiveStreamType2 + " mVolumeControlStream:" + this.mVolumeControlStream);
                if (!activeForReal) {
                    streamType = this.mVolumeControlStream;
                }
                streamType = maybeActiveStreamType2;
            }
            maybeActiveStreamType = streamType;
        }
        boolean isMute = isMuteAdjust(direction);
        ensureValidStreamType(maybeActiveStreamType);
        int resolvedStream = mStreamVolumeAlias[maybeActiveStreamType];
        if ((flags3 & 4) != 0 && resolvedStream != 2) {
            flags3 &= -5;
        }
        if (this.mUseFixedVolume || !this.mVolumeController.suppressAdjustment(resolvedStream, flags3, isMute)) {
            direction2 = direction;
            flags2 = flags3;
        } else {
            int flags4 = flags3 & (-5) & (-17);
            if (DEBUG_VOL) {
                Log.d(TAG, "Volume controller suppressed adjustment");
            }
            direction2 = 0;
            flags2 = flags4;
        }
        adjustStreamVolume(maybeActiveStreamType, direction2, flags2, callingPackage, caller, uid);
    }

    public void adjustStreamVolume(int streamType, int direction, int flags, String callingPackage) {
        if (streamType == 10 && !canChangeAccessibilityVolume()) {
            Log.w(TAG, "Trying to call adjustStreamVolume() for a11y withoutCHANGE_ACCESSIBILITY_VOLUME / callingPackage=" + callingPackage);
        } else if (!allowAdjustVolume(callingPackage)) {
            Log.d(TAG, "do not allowed to adjustStreamVolume(stream=" + streamType + ", direction=" + direction + ", calling=" + callingPackage + ")");
        } else {
            this.mVolumeLogger.log(new AudioServiceEvents.VolumeEvent(1, streamType, direction, flags, callingPackage));
            adjustStreamVolume(streamType, direction, flags, callingPackage, callingPackage, Binder.getCallingUid());
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:130:0x0251  */
    /* JADX WARN: Removed duplicated region for block: B:148:0x0275  */
    /* JADX WARN: Removed duplicated region for block: B:150:0x027a  */
    /* JADX WARN: Removed duplicated region for block: B:151:0x0284 A[DONT_GENERATE] */
    /* JADX WARN: Removed duplicated region for block: B:154:0x028a  */
    /* JADX WARN: Removed duplicated region for block: B:215:0x025c A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    protected void adjustStreamVolume(int r27, int r28, int r29, java.lang.String r30, java.lang.String r31, int r32) {
        /*
            Method dump skipped, instructions count: 747
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.audio.AudioService.adjustStreamVolume(int, int, int, java.lang.String, java.lang.String, int):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onUnmuteStream(int stream, int flags) {
        VolumeStreamState streamState = this.mStreamStates[stream];
        streamState.mute(false);
        int device = getDeviceForStream(stream);
        int index = this.mStreamStates[stream].getIndex(device);
        sendVolumeUpdate(stream, index, index, flags);
    }

    private void setSystemAudioVolume(int oldVolume, int newVolume, int maxVolume, int flags) {
        if (this.mHdmiManager == null || this.mHdmiTvClient == null || oldVolume == newVolume || (flags & 256) != 0) {
            return;
        }
        synchronized (this.mHdmiManager) {
            if (this.mHdmiSystemAudioSupported) {
                synchronized (this.mHdmiTvClient) {
                    long token = Binder.clearCallingIdentity();
                    this.mHdmiTvClient.setSystemAudioVolume(oldVolume, newVolume, maxVolume);
                    Binder.restoreCallingIdentity(token);
                }
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
            if (index != 0) {
                return 2;
            }
            if (this.mHasVibrator) {
                return 1;
            }
            return this.mVolumePolicy.volumeDownToEnterSilent ? 0 : 2;
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
            setRingerMode(getNewRingerMode(stream, index, flags), "AudioService.onSetStreamVolume", false);
        }
        this.mStreamStates[stream].mute(index == 0);
    }

    public void setStreamVolume(int streamType, int index, int flags, String callingPackage) {
        Log.d(TAG, "setStreamVolume streamType:" + streamType + " index:" + index + " callingPackage:" + callingPackage);
        if (streamType == 10 && !canChangeAccessibilityVolume()) {
            Log.w(TAG, "Trying to call setStreamVolume() for a11y without CHANGE_ACCESSIBILITY_VOLUME  callingPackage=" + callingPackage);
        } else if (streamType != 0 || index != 0 || this.mContext.checkCallingOrSelfPermission("android.permission.MODIFY_PHONE_STATE") == 0) {
            this.mVolumeLogger.log(new AudioServiceEvents.VolumeEvent(2, streamType, index, flags, callingPackage));
            setStreamVolume(streamType, index, flags, callingPackage, callingPackage, Binder.getCallingUid());
        } else {
            Log.w(TAG, "Trying to call setStreamVolume() for STREAM_VOICE_CALL and index 0 without MODIFY_PHONE_STATE  callingPackage=" + callingPackage);
        }
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

    public void setVolumeToHal(int streamType) {
        Log.d(TAG, "setVolumeToHal(stream=" + streamType + ")");
        if (this.mUseFixedVolume) {
            if (newPolicyOpen) {
                if (this.mXuiAudioPolicyTrigger != null) {
                    this.mXuiAudioPolicyTrigger.setVolWhenPlay(streamType);
                }
            } else if (this.mXpAudio != null) {
                this.mXpAudio.setStreamVolume(streamType);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:118:0x015e A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:94:0x0186  */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:107:0x01b7 -> B:108:0x01b8). Please submit an issue!!! */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void setStreamVolume(int r19, int r20, int r21, java.lang.String r22, java.lang.String r23, int r24) {
        /*
            Method dump skipped, instructions count: 442
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.audio.AudioService.setStreamVolume(int, int, int, java.lang.String, java.lang.String, int):void");
    }

    private boolean volumeAdjustmentAllowedByDnd(int streamTypeAlias, int flags) {
        switch (this.mNm.getZenMode()) {
            case 0:
                return true;
            case 1:
            case 2:
            case 3:
                return (isStreamMutedByRingerOrZenMode(streamTypeAlias) && streamTypeAlias != getUiSoundsStreamType() && (flags & 2) == 0) ? false : true;
            default:
                return true;
        }
    }

    public void forceVolumeControlStream(int streamType, IBinder cb) {
        if (DEBUG_VOL) {
            Log.d(TAG, String.format("forceVolumeControlStream(%d)", Integer.valueOf(streamType)));
        }
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
            if (this.mCb != null) {
                this.mCb.unlinkToDeath(this, 0);
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

    /* JADX INFO: Access modifiers changed from: private */
    public void sendStickyBroadcastToAll(Intent intent) {
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

    protected void sendVolumeUpdate(int streamType, int oldIndex, int index, int flags) {
        int streamType2 = mStreamVolumeAlias[streamType];
        if (streamType2 == 3) {
            flags = updateFlagsForSystemAudio(flags);
        }
        this.mVolumeController.postVolumeChanged(streamType2, flags);
    }

    private int updateFlagsForSystemAudio(int flags) {
        if (this.mHdmiTvClient != null) {
            synchronized (this.mHdmiTvClient) {
                if (this.mHdmiSystemAudioSupported && (flags & 256) == 0) {
                    flags &= -2;
                }
            }
        }
        return flags;
    }

    private void sendMasterMuteUpdate(boolean muted, int flags) {
        this.mVolumeController.postMasterMuteChanged(updateFlagsForSystemAudio(flags));
        broadcastMasterMuteStatus(muted);
    }

    private void broadcastMasterMuteStatus(boolean muted) {
        Intent intent = new Intent("android.media.MASTER_MUTE_CHANGED_ACTION");
        intent.putExtra("android.media.EXTRA_MASTER_VOLUME_MUTED", muted);
        intent.addFlags(603979776);
        sendStickyBroadcastToAll(intent);
    }

    private void setStreamVolumeInt(int streamType, int index, int device, boolean force, String caller) {
        VolumeStreamState streamState = this.mStreamStates[streamType];
        if (streamState.setIndex(index, device, caller) || force) {
            sendMsg(this.mAudioHandler, 0, 2, device, 0, streamState, 0);
        }
    }

    private void setSystemAudioMute(boolean state) {
        if (this.mHdmiManager == null || this.mHdmiTvClient == null) {
            return;
        }
        synchronized (this.mHdmiManager) {
            if (this.mHdmiSystemAudioSupported) {
                synchronized (this.mHdmiTvClient) {
                    long token = Binder.clearCallingIdentity();
                    this.mHdmiTvClient.setSystemAudioMute(state);
                    Binder.restoreCallingIdentity(token);
                }
            }
        }
    }

    public boolean isStreamMute(int streamType) {
        boolean z;
        if (this.mUseFixedVolume) {
            if (newPolicyOpen) {
                if (this.mXuiAudioPolicyTrigger != null) {
                    return this.mXuiAudioPolicyTrigger.getStreamMute(streamType);
                }
            } else if (this.mXpAudio != null) {
                return this.mXpAudio.getStreamMute(streamType);
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
            try {
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
            } catch (Throwable th) {
                throw th;
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
        if (DEBUG_VOL) {
            Log.d(TAG, String.format("Master mute %s, %d, user=%d", Boolean.valueOf(mute), Integer.valueOf(flags), Integer.valueOf(userId)));
        }
        if ((isPlatformAutomotive() || !this.mUseFixedVolume) && getCurrentUserId() == userId && mute != AudioSystem.getMasterMute()) {
            setSystemAudioMute(mute);
            AudioSystem.setMasterMute(mute);
            sendMasterMuteUpdate(mute, flags);
            Intent intent = new Intent("android.media.MASTER_MUTE_CHANGED_ACTION");
            intent.putExtra("android.media.EXTRA_MASTER_VOLUME_MUTED", mute);
            sendBroadcastToAll(intent);
        }
    }

    public boolean isMasterMute() {
        return AudioSystem.getMasterMute();
    }

    public void setMasterMute(boolean mute, int flags, String callingPackage, int userId) {
        setMasterMuteInternal(mute, flags, callingPackage, Binder.getCallingUid(), userId);
    }

    public int getStreamVolume(int streamType) {
        int i;
        ensureValidStreamType(streamType);
        if (this.mUseFixedVolume) {
            if (newPolicyOpen) {
                if (this.mXuiAudioPolicyTrigger != null) {
                    return this.mXuiAudioPolicyTrigger.getStreamVolume(streamType);
                }
            } else if (this.mXpAudio != null) {
                return this.mXpAudio.getStreamVolume(streamType);
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
                if (this.mXuiAudioPolicyTrigger != null) {
                    return this.mXuiAudioPolicyTrigger.getStreamMaxVolume(streamType);
                }
            } else if (this.mXpAudio != null) {
                return this.mXpAudio.getStreamMaxVolume(streamType);
            }
        }
        return (this.mStreamStates[streamType].getMaxIndex() + 5) / 10;
    }

    public int getStreamMinVolume(int streamType) {
        ensureValidStreamType(streamType);
        if (this.mUseFixedVolume) {
            if (newPolicyOpen) {
                if (this.mXuiAudioPolicyTrigger != null) {
                    return this.mXuiAudioPolicyTrigger.getStreamMinVolume(streamType);
                }
            } else if (this.mXpAudio != null) {
                return this.mXpAudio.getStreamMinVolume(streamType);
            }
        }
        return (this.mStreamStates[streamType].getMinIndex() + 5) / 10;
    }

    public int getLastAudibleStreamVolume(int streamType) {
        ensureValidStreamType(streamType);
        if (this.mUseFixedVolume) {
            if (newPolicyOpen) {
                if (this.mXuiAudioPolicyTrigger != null) {
                    return this.mXuiAudioPolicyTrigger.getLastAudibleStreamVolume(streamType);
                }
            } else if (this.mXpAudio != null) {
                return this.mXpAudio.getLastAudibleStreamVolume(streamType);
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
        if (DEBUG_VOL) {
            Log.d(TAG, String.format("Mic mute %s, user=%d", Boolean.valueOf(on), Integer.valueOf(userId)));
        }
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
        if (this.mContext.getResources().getBoolean(17957076)) {
            silenceRingerSetting = Settings.Secure.getIntForUser(this.mContentResolver, "volume_hush_gesture", 0, -2);
        }
        switch (silenceRingerSetting) {
            case 1:
                effect = VibrationEffect.get(5);
                ringerMode = 1;
                toastText = 17041047;
                break;
            case 2:
                effect = VibrationEffect.get(1);
                ringerMode = 0;
                toastText = 17041046;
                break;
        }
        maybeVibrate(effect);
        setRingerModeInternal(ringerMode, reason);
        Toast.makeText(this.mContext, toastText, 0).show();
    }

    private boolean maybeVibrate(VibrationEffect effect) {
        if (this.mHasVibrator) {
            boolean hapticsDisabled = Settings.System.getIntForUser(this.mContext.getContentResolver(), "haptic_feedback_enabled", 0, -2) == 0;
            if (hapticsDisabled || effect == null) {
                return false;
            }
            this.mVibrator.vibrate(Binder.getCallingUid(), this.mContext.getOpPackageName(), effect, VIBRATION_ATTRIBUTES);
            return true;
        }
        return false;
    }

    private void setRingerMode(int ringerMode, String caller, boolean external) {
        if (this.mUseFixedVolume || this.mIsSingleVolume) {
            return;
        }
        if (caller == null || caller.length() == 0) {
            throw new IllegalArgumentException("Bad caller: " + caller);
        }
        ensureValidRingerMode(ringerMode);
        int i = ringerMode;
        if (i == 1 && !this.mHasVibrator) {
            i = 0;
        }
        int ringerMode2 = i;
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

    private void setRingerModeExt(int ringerMode) {
        synchronized (this.mSettingsLock) {
            if (ringerMode == this.mRingerModeExternal) {
                return;
            }
            this.mRingerModeExternal = ringerMode;
            broadcastRingerMode("android.media.RINGER_MODE_CHANGED", ringerMode);
        }
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:72:? -> B:61:0x00ec). Please submit an issue!!! */
    @GuardedBy("mSettingsLock")
    private void muteRingerModeStreams() {
        int numStreamTypes;
        int numStreamTypes2;
        int numStreamTypes3 = AudioSystem.getNumStreamTypes();
        if (this.mNm == null) {
            this.mNm = (NotificationManager) this.mContext.getSystemService("notification");
        }
        int ringerMode = this.mRingerMode;
        boolean z = true;
        boolean ringerModeMute = ringerMode == 1 || ringerMode == 0;
        boolean shouldRingSco = ringerMode == 1 && isBluetoothScoOn();
        String eventSource = "muteRingerModeStreams() from u/pid:" + Binder.getCallingUid() + SliceClientPermissions.SliceAuthority.DELIMITER + Binder.getCallingPid();
        sendMsg(this.mAudioHandler, 8, 2, 7, shouldRingSco ? 3 : 0, eventSource, 0);
        int streamType = numStreamTypes3 - 1;
        while (streamType >= 0) {
            boolean isMuted = isStreamMutedByRingerOrZenMode(streamType);
            boolean muteAllowedBySco = (shouldRingSco && streamType == 2) ? false : z ? 1 : 0;
            boolean shouldZenMute = shouldZenMuteStream(streamType);
            boolean shouldMute = (shouldZenMute || (ringerModeMute && isStreamAffectedByRingerMode(streamType) && muteAllowedBySco)) ? z ? 1 : 0 : false;
            if (isMuted == shouldMute) {
                numStreamTypes = numStreamTypes3;
            } else if (shouldMute) {
                numStreamTypes = numStreamTypes3;
                this.mStreamStates[streamType].mute(z);
                this.mRingerAndZenModeMutedStreams |= (z ? 1 : 0) << streamType;
            } else {
                if (mStreamVolumeAlias[streamType] == 2) {
                    synchronized (VolumeStreamState.class) {
                        try {
                            VolumeStreamState vss = this.mStreamStates[streamType];
                            int i = 0;
                            while (i < vss.mIndexMap.size()) {
                                int device = vss.mIndexMap.keyAt(i);
                                int value = vss.mIndexMap.valueAt(i);
                                if (value == 0) {
                                    numStreamTypes2 = numStreamTypes3;
                                    try {
                                        vss.setIndex(10, device, TAG);
                                    } catch (Throwable th) {
                                        th = th;
                                        throw th;
                                    }
                                } else {
                                    numStreamTypes2 = numStreamTypes3;
                                }
                                i++;
                                numStreamTypes3 = numStreamTypes2;
                            }
                            numStreamTypes = numStreamTypes3;
                            int device2 = getDeviceForStream(streamType);
                            sendMsg(this.mAudioHandler, 1, 2, device2, 0, this.mStreamStates[streamType], 500);
                        } catch (Throwable th2) {
                            th = th2;
                            throw th;
                        }
                    }
                } else {
                    numStreamTypes = numStreamTypes3;
                }
                this.mStreamStates[streamType].mute(false);
                z = true;
                this.mRingerAndZenModeMutedStreams &= ~(1 << streamType);
            }
            streamType--;
            numStreamTypes3 = numStreamTypes;
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

    public boolean shouldVibrate(int vibrateType) {
        if (this.mHasVibrator) {
            switch (getVibrateSetting(vibrateType)) {
                case 0:
                    return false;
                case 1:
                    return getRingerModeExternal() != 0;
                case 2:
                    return getRingerModeExternal() == 1;
                default:
                    return false;
            }
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
            synchronized (AudioService.this.mSetModeDeathHandlers) {
                Log.w(AudioService.TAG, "setMode() client died");
                if (!AudioService.this.mSetModeDeathHandlers.isEmpty()) {
                    oldModeOwnerPid = ((SetModeDeathHandler) AudioService.this.mSetModeDeathHandlers.get(0)).getPid();
                }
                int index = AudioService.this.mSetModeDeathHandlers.indexOf(this);
                if (index >= 0) {
                    newModeOwnerPid = AudioService.this.setModeInt(0, this.mCb, this.mPid, AudioService.TAG);
                } else {
                    Log.w(AudioService.TAG, "unregistered setMode() client died");
                }
            }
            if (newModeOwnerPid != oldModeOwnerPid && newModeOwnerPid != 0) {
                long ident = Binder.clearCallingIdentity();
                AudioService.this.disconnectBluetoothSco(newModeOwnerPid);
                Binder.restoreCallingIdentity(ident);
            }
        }

        public int getPid() {
            return this.mPid;
        }

        public void setMode(int mode) {
            Log.d(AudioService.TAG, "setMode " + mode);
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
        Log.v(TAG, "setMode(mode=" + mode + ", callingPackage=" + callingPackage + ")");
        if (!checkAudioSettingsPermission("setMode()")) {
            return;
        }
        if (mode == 2 && this.mContext.checkCallingOrSelfPermission("android.permission.MODIFY_PHONE_STATE") != 0) {
            Log.w(TAG, "MODIFY_PHONE_STATE Permission Denial: setMode(MODE_IN_CALL) from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
        } else if (mode < -1 || mode >= 4) {
        } else {
            int oldModeOwnerPid = 0;
            synchronized (this.mSetModeDeathHandlers) {
                if (!this.mSetModeDeathHandlers.isEmpty()) {
                    oldModeOwnerPid = this.mSetModeDeathHandlers.get(0).getPid();
                }
                if (mode == -1) {
                    mode = this.mMode;
                }
                newModeOwnerPid = setModeInt(mode, cb, Binder.getCallingPid(), callingPackage);
            }
            if (newModeOwnerPid != oldModeOwnerPid && newModeOwnerPid != 0) {
                disconnectBluetoothSco(newModeOwnerPid);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:29:0x00e8  */
    /* JADX WARN: Removed duplicated region for block: B:36:0x0124  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public int setModeInt(int r19, android.os.IBinder r20, int r21, java.lang.String r22) {
        /*
            Method dump skipped, instructions count: 410
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.audio.AudioService.setModeInt(int, android.os.IBinder, int, java.lang.String):int");
    }

    public int getMode() {
        return this.mMode;
    }

    /* loaded from: classes.dex */
    class LoadSoundEffectReply {
        public int mStatus = 1;

        LoadSoundEffectReply() {
        }
    }

    private void loadTouchSoundAssetDefaults() {
        SOUND_EFFECT_FILES.add("Effect_Tick.ogg");
        for (int i = 0; i < 18; i++) {
            this.SOUND_EFFECT_FILES_MAP[i][0] = 0;
            this.SOUND_EFFECT_FILES_MAP[i][1] = -1;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void loadTouchSoundAssets() {
        XmlResourceParser parser = null;
        if (SOUND_EFFECT_FILES.isEmpty()) {
            loadTouchSoundAssetDefaults();
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
                    if (parser == null) {
                        return;
                    }
                } catch (Resources.NotFoundException e2) {
                    Log.w(TAG, "audio assets file not found", e2);
                    if (parser == null) {
                        return;
                    }
                } catch (IOException e3) {
                    Log.w(TAG, "I/O exception reading touch sound assets", e3);
                    if (parser == null) {
                        return;
                    }
                } catch (XmlPullParserException e4) {
                    Log.w(TAG, "XML parser exception reading touch sound assets", e4);
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
            } else if (effectType >= 18 || effectType < 0) {
                Log.w(TAG, "AudioService effectType value " + effectType + " out of range");
            } else {
                sendMsg(this.mAudioHandler, 5, 2, effectType, (int) (1000.0f * volume), null, 0);
            }
        }
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:34:? -> B:28:0x003c). Please submit an issue!!! */
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
                        } catch (InterruptedException e) {
                            Log.w(TAG, "loadSoundEffects Interrupted while waiting sound pool loaded.");
                        }
                        attempts = attempts2;
                    } catch (Throwable th2) {
                        th = th2;
                        throw th;
                    }
                }
                return reply.mStatus == 0;
            } catch (Throwable th3) {
                th = th3;
                throw th;
            }
        }
    }

    public void unloadSoundEffects() {
        sendMsg(this.mAudioHandler, 20, 2, 0, 0, null, 0);
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
        synchronized (this.mSafeMediaVolumeState) {
            this.mMusicActiveMs = MathUtils.constrain(Settings.Secure.getIntForUser(this.mContentResolver, "unsafe_volume_music_active_ms", 0, -2), 0, (int) UNSAFE_VOLUME_MUSIC_ACTIVE_MS_MAX);
            if (this.mSafeMediaVolumeState.intValue() == 3) {
                enforceSafeMediaVolume(TAG);
            }
        }
    }

    public void setSpeakerphoneOn(boolean on) {
        if (!checkAudioSettingsPermission("setSpeakerphoneOn()")) {
            return;
        }
        String eventSource = "setSpeakerphoneOn(" + on + ") from u/pid:" + Binder.getCallingUid() + SliceClientPermissions.SliceAuthority.DELIMITER + Binder.getCallingPid();
        if (!on) {
            if (this.mForcedUseForComm == 1) {
                this.mForcedUseForComm = 0;
            }
        } else {
            if (this.mForcedUseForComm == 3) {
                sendMsg(this.mAudioHandler, 8, 2, 2, 0, eventSource, 0);
            }
            this.mForcedUseForComm = 1;
        }
        this.mForcedUseForCommExt = this.mForcedUseForComm;
        sendMsg(this.mAudioHandler, 8, 2, 0, this.mForcedUseForComm, eventSource, 0);
    }

    public boolean isSpeakerphoneOn() {
        return this.mForcedUseForCommExt == 1;
    }

    public void setBluetoothScoOn(boolean on) {
        if (!checkAudioSettingsPermission("setBluetoothScoOn()")) {
            return;
        }
        if (Binder.getCallingUid() >= 10000) {
            this.mForcedUseForCommExt = on ? 3 : 0;
            return;
        }
        String eventSource = "setBluetoothScoOn(" + on + ") from u/pid:" + Binder.getCallingUid() + SliceClientPermissions.SliceAuthority.DELIMITER + Binder.getCallingPid();
        setBluetoothScoOnInt(on, eventSource);
    }

    public void setBluetoothScoOnInt(boolean on, String eventSource) {
        Log.i(TAG, "setBluetoothScoOnInt: " + on + " " + eventSource);
        if (on) {
            synchronized (this.mScoClients) {
                if (this.mBluetoothHeadset != null && this.mBluetoothHeadset.getAudioState(this.mBluetoothHeadsetDevice) != 12) {
                    this.mForcedUseForCommExt = 3;
                    Log.w(TAG, "setBluetoothScoOnInt(true) failed because " + this.mBluetoothHeadsetDevice + " is not in audio connected mode");
                    return;
                }
                this.mForcedUseForComm = 3;
            }
        } else if (this.mForcedUseForComm == 3) {
            this.mForcedUseForComm = 0;
        }
        this.mForcedUseForCommExt = this.mForcedUseForComm;
        StringBuilder sb = new StringBuilder();
        sb.append("BT_SCO=");
        sb.append(on ? "on" : "off");
        AudioSystem.setParameters(sb.toString());
        sendMsg(this.mAudioHandler, 8, 2, 0, this.mForcedUseForComm, eventSource, 0);
        sendMsg(this.mAudioHandler, 8, 2, 2, this.mForcedUseForComm, eventSource, 0);
        setRingerModeInt(getRingerModeInternal(), false);
    }

    public boolean isBluetoothScoOn() {
        return this.mForcedUseForCommExt == 3;
    }

    public void setBluetoothA2dpOn(boolean on) {
        String eventSource = "setBluetoothA2dpOn(" + on + ") from u/pid:" + Binder.getCallingUid() + SliceClientPermissions.SliceAuthority.DELIMITER + Binder.getCallingPid();
        synchronized (this.mBluetoothA2dpEnabledLock) {
            if (this.mBluetoothA2dpEnabled == on) {
                return;
            }
            this.mBluetoothA2dpEnabled = on;
            sendMsg(this.mAudioHandler, 13, 2, 1, this.mBluetoothA2dpEnabled ? 0 : 10, eventSource, 0);
        }
    }

    public boolean isBluetoothA2dpOn() {
        boolean z;
        synchronized (this.mBluetoothA2dpEnabledLock) {
            z = this.mBluetoothA2dpEnabled;
        }
        return z;
    }

    public void startBluetoothSco(IBinder cb, int targetSdkVersion) {
        int scoAudioMode = targetSdkVersion < 18 ? 0 : -1;
        startBluetoothScoInt(cb, scoAudioMode);
    }

    public void startBluetoothScoVirtualCall(IBinder cb) {
        startBluetoothScoInt(cb, 0);
    }

    void startBluetoothScoInt(IBinder cb, int scoAudioMode) {
        if (!checkAudioSettingsPermission("startBluetoothSco()") || !this.mSystemReady) {
            return;
        }
        ScoClient client = getScoClient(cb, true);
        long ident = Binder.clearCallingIdentity();
        client.incCount(scoAudioMode);
        Binder.restoreCallingIdentity(ident);
    }

    public void stopBluetoothSco(IBinder cb) {
        if (!checkAudioSettingsPermission("stopBluetoothSco()") || !this.mSystemReady) {
            return;
        }
        ScoClient client = getScoClient(cb, false);
        long ident = Binder.clearCallingIdentity();
        if (client != null) {
            client.decCount();
        }
        Binder.restoreCallingIdentity(ident);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class ScoClient implements IBinder.DeathRecipient {
        private IBinder mCb;
        private int mCreatorPid = Binder.getCallingPid();
        private int mStartcount = 0;

        ScoClient(IBinder cb) {
            this.mCb = cb;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            synchronized (AudioService.this.mScoClients) {
                Log.w(AudioService.TAG, "SCO client died");
                int index = AudioService.this.mScoClients.indexOf(this);
                if (index < 0) {
                    Log.w(AudioService.TAG, "unregistered SCO client died");
                } else {
                    clearCount(true);
                    AudioService.this.mScoClients.remove(this);
                }
            }
        }

        public void incCount(int scoAudioMode) {
            synchronized (AudioService.this.mScoClients) {
                requestScoState(12, scoAudioMode);
                if (this.mStartcount == 0) {
                    try {
                        this.mCb.linkToDeath(this, 0);
                    } catch (RemoteException e) {
                        Log.w(AudioService.TAG, "ScoClient  incCount() could not link to " + this.mCb + " binder death");
                    }
                }
                this.mStartcount++;
            }
        }

        public void decCount() {
            synchronized (AudioService.this.mScoClients) {
                if (this.mStartcount == 0) {
                    Log.w(AudioService.TAG, "ScoClient.decCount() already 0");
                } else {
                    this.mStartcount--;
                    if (this.mStartcount == 0) {
                        try {
                            this.mCb.unlinkToDeath(this, 0);
                        } catch (NoSuchElementException e) {
                            Log.w(AudioService.TAG, "decCount() going to 0 but not registered to binder");
                        }
                    }
                    requestScoState(10, 0);
                }
            }
        }

        public void clearCount(boolean stopSco) {
            synchronized (AudioService.this.mScoClients) {
                if (this.mStartcount != 0) {
                    try {
                        this.mCb.unlinkToDeath(this, 0);
                    } catch (NoSuchElementException e) {
                        Log.w(AudioService.TAG, "clearCount() mStartcount: " + this.mStartcount + " != 0 but not registered to binder");
                    }
                }
                this.mStartcount = 0;
                if (stopSco) {
                    requestScoState(10, 0);
                }
            }
        }

        public int getCount() {
            return this.mStartcount;
        }

        public IBinder getBinder() {
            return this.mCb;
        }

        public int getPid() {
            return this.mCreatorPid;
        }

        public int totalCount() {
            int count;
            synchronized (AudioService.this.mScoClients) {
                count = 0;
                Iterator it = AudioService.this.mScoClients.iterator();
                while (it.hasNext()) {
                    ScoClient mScoClient = (ScoClient) it.next();
                    count += mScoClient.getCount();
                }
            }
            return count;
        }

        private void requestScoState(int state, int scoAudioMode) {
            int modeOwnerPid;
            AudioService.this.checkScoAudioState();
            int clientCount = totalCount();
            if (clientCount != 0) {
                Log.i(AudioService.TAG, "requestScoState: state=" + state + ", scoAudioMode=" + scoAudioMode + ", clientCount=" + clientCount);
            } else if (state == 12) {
                AudioService.this.broadcastScoConnectionState(2);
                synchronized (AudioService.this.mSetModeDeathHandlers) {
                    if (!AudioService.this.mSetModeDeathHandlers.isEmpty()) {
                        modeOwnerPid = ((SetModeDeathHandler) AudioService.this.mSetModeDeathHandlers.get(0)).getPid();
                    } else {
                        modeOwnerPid = 0;
                    }
                    if (modeOwnerPid == 0 || modeOwnerPid == this.mCreatorPid) {
                        int i = AudioService.this.mScoAudioState;
                        if (i != 0) {
                            switch (i) {
                                case 4:
                                    AudioService.this.mScoAudioState = 3;
                                    AudioService.this.broadcastScoConnectionState(1);
                                    break;
                                case 5:
                                    AudioService.this.mScoAudioState = 1;
                                    break;
                                default:
                                    Log.w(AudioService.TAG, "requestScoState: failed to connect in state " + AudioService.this.mScoAudioState + ", scoAudioMode=" + scoAudioMode);
                                    AudioService.this.broadcastScoConnectionState(0);
                                    break;
                            }
                        } else {
                            AudioService.this.mScoAudioMode = scoAudioMode;
                            if (scoAudioMode == -1) {
                                AudioService.this.mScoAudioMode = 0;
                                if (AudioService.this.mBluetoothHeadsetDevice != null) {
                                    AudioService.this.mScoAudioMode = Settings.Global.getInt(AudioService.this.mContentResolver, "bluetooth_sco_channel_" + AudioService.this.mBluetoothHeadsetDevice.getAddress(), 0);
                                    if (AudioService.this.mScoAudioMode > 2 || AudioService.this.mScoAudioMode < 0) {
                                        AudioService.this.mScoAudioMode = 0;
                                    }
                                }
                            }
                            if (AudioService.this.mBluetoothHeadset == null) {
                                if (AudioService.this.getBluetoothHeadset()) {
                                    AudioService.this.mScoAudioState = 1;
                                } else {
                                    Log.w(AudioService.TAG, "requestScoState: getBluetoothHeadset failed during connection, mScoAudioMode=" + AudioService.this.mScoAudioMode);
                                    AudioService.this.broadcastScoConnectionState(0);
                                }
                            } else if (AudioService.this.mBluetoothHeadsetDevice == null) {
                                Log.w(AudioService.TAG, "requestScoState: no active device while connecting, mScoAudioMode=" + AudioService.this.mScoAudioMode);
                                AudioService.this.broadcastScoConnectionState(0);
                            } else if (AudioService.connectBluetoothScoAudioHelper(AudioService.this.mBluetoothHeadset, AudioService.this.mBluetoothHeadsetDevice, AudioService.this.mScoAudioMode)) {
                                AudioService.this.mScoAudioState = 3;
                            } else {
                                Log.w(AudioService.TAG, "requestScoState: connect to " + AudioService.this.mBluetoothHeadsetDevice + " failed, mScoAudioMode=" + AudioService.this.mScoAudioMode);
                                AudioService.this.broadcastScoConnectionState(0);
                            }
                        }
                        return;
                    }
                    Log.w(AudioService.TAG, "requestScoState: audio mode is not NORMAL and modeOwnerPid " + modeOwnerPid + " != creatorPid " + this.mCreatorPid);
                    AudioService.this.broadcastScoConnectionState(0);
                }
            } else if (state == 10) {
                int i2 = AudioService.this.mScoAudioState;
                if (i2 == 1) {
                    AudioService.this.mScoAudioState = 0;
                    AudioService.this.broadcastScoConnectionState(0);
                } else if (i2 != 3) {
                    Log.w(AudioService.TAG, "requestScoState: failed to disconnect in state " + AudioService.this.mScoAudioState + ", scoAudioMode=" + scoAudioMode);
                    AudioService.this.broadcastScoConnectionState(0);
                } else if (AudioService.this.mBluetoothHeadset == null) {
                    if (AudioService.this.getBluetoothHeadset()) {
                        AudioService.this.mScoAudioState = 4;
                        return;
                    }
                    Log.w(AudioService.TAG, "requestScoState: getBluetoothHeadset failed during disconnection, mScoAudioMode=" + AudioService.this.mScoAudioMode);
                    AudioService.this.mScoAudioState = 0;
                    AudioService.this.broadcastScoConnectionState(0);
                } else if (AudioService.this.mBluetoothHeadsetDevice == null) {
                    AudioService.this.mScoAudioState = 0;
                    AudioService.this.broadcastScoConnectionState(0);
                } else if (AudioService.disconnectBluetoothScoAudioHelper(AudioService.this.mBluetoothHeadset, AudioService.this.mBluetoothHeadsetDevice, AudioService.this.mScoAudioMode)) {
                    AudioService.this.mScoAudioState = 5;
                } else {
                    AudioService.this.mScoAudioState = 0;
                    AudioService.this.broadcastScoConnectionState(0);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkScoAudioState() {
        synchronized (this.mScoClients) {
            if (this.mBluetoothHeadset != null && this.mBluetoothHeadsetDevice != null && this.mScoAudioState == 0 && this.mBluetoothHeadset.getAudioState(this.mBluetoothHeadsetDevice) != 10) {
                this.mScoAudioState = 2;
            }
        }
    }

    private ScoClient getScoClient(IBinder cb, boolean create) {
        synchronized (this.mScoClients) {
            Iterator<ScoClient> it = this.mScoClients.iterator();
            while (it.hasNext()) {
                ScoClient existingClient = it.next();
                if (existingClient.getBinder() == cb) {
                    return existingClient;
                }
            }
            if (create) {
                ScoClient newClient = new ScoClient(cb);
                this.mScoClients.add(newClient);
                return newClient;
            }
            return null;
        }
    }

    public void clearAllScoClients(int exceptPid, boolean stopSco) {
        synchronized (this.mScoClients) {
            ScoClient savedClient = null;
            Iterator<ScoClient> it = this.mScoClients.iterator();
            while (it.hasNext()) {
                ScoClient cl = it.next();
                if (cl.getPid() != exceptPid) {
                    cl.clearCount(stopSco);
                } else {
                    savedClient = cl;
                }
            }
            this.mScoClients.clear();
            if (savedClient != null) {
                this.mScoClients.add(savedClient);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean getBluetoothHeadset() {
        boolean result = false;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            result = adapter.getProfileProxy(this.mContext, this.mBluetoothProfileServiceListener, 1);
        }
        sendMsg(this.mAudioHandler, 9, 0, 0, 0, null, result ? BT_HEADSET_CNCT_TIMEOUT_MS : 0);
        return result;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void disconnectBluetoothSco(int exceptPid) {
        synchronized (this.mScoClients) {
            checkScoAudioState();
            if (this.mScoAudioState == 2) {
                return;
            }
            clearAllScoClients(exceptPid, true);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean disconnectBluetoothScoAudioHelper(BluetoothHeadset bluetoothHeadset, BluetoothDevice device, int scoAudioMode) {
        switch (scoAudioMode) {
            case 0:
                return bluetoothHeadset.stopScoUsingVirtualVoiceCall();
            case 1:
                return bluetoothHeadset.disconnectAudio();
            case 2:
                return bluetoothHeadset.stopVoiceRecognition(device);
            default:
                return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean connectBluetoothScoAudioHelper(BluetoothHeadset bluetoothHeadset, BluetoothDevice device, int scoAudioMode) {
        switch (scoAudioMode) {
            case 0:
                return bluetoothHeadset.startScoUsingVirtualVoiceCall();
            case 1:
                return bluetoothHeadset.connectAudio();
            case 2:
                return bluetoothHeadset.startVoiceRecognition(device);
            default:
                return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void resetBluetoothSco() {
        synchronized (this.mScoClients) {
            clearAllScoClients(0, false);
            this.mScoAudioState = 0;
            broadcastScoConnectionState(0);
        }
        AudioSystem.setParameters("A2dpSuspended=false");
        setBluetoothScoOnInt(false, "resetBluetoothSco");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void broadcastScoConnectionState(int state) {
        sendMsg(this.mAudioHandler, 19, 2, state, 0, null, 0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onBroadcastScoConnectionState(int state) {
        if (state != this.mScoConnectionState) {
            Intent newIntent = new Intent("android.media.ACTION_SCO_AUDIO_STATE_UPDATED");
            newIntent.putExtra("android.media.extra.SCO_AUDIO_STATE", state);
            newIntent.putExtra("android.media.extra.SCO_AUDIO_PREVIOUS_STATE", this.mScoConnectionState);
            sendStickyBroadcastToAll(newIntent);
            this.mScoConnectionState = state;
        }
    }

    private boolean handleBtScoActiveDeviceChange(BluetoothDevice btDevice, boolean isActive) {
        boolean result;
        if (btDevice == null) {
            return true;
        }
        String address = btDevice.getAddress();
        BluetoothClass btClass = btDevice.getBluetoothClass();
        int[] outDeviceTypes = {16, 32, 64};
        if (btClass != null) {
            int deviceClass = btClass.getDeviceClass();
            if (deviceClass == 1028 || deviceClass == 1032) {
                outDeviceTypes = new int[]{32};
            } else if (deviceClass == 1056) {
                outDeviceTypes = new int[]{64};
            }
        }
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
        String btDeviceName = btDevice.getName();
        if (isActive) {
            result = false | handleDeviceConnection(isActive, outDeviceTypes[0], address, btDeviceName);
        } else {
            boolean result2 = false;
            for (int outDeviceType : outDeviceTypes) {
                result2 |= handleDeviceConnection(isActive, outDeviceType, address, btDeviceName);
            }
            result = result2;
        }
        if (handleDeviceConnection(isActive, -2147483640, address, btDeviceName) && result) {
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setBtScoActiveDevice(BluetoothDevice btDevice) {
        synchronized (this.mScoClients) {
            Log.i(TAG, "setBtScoActiveDevice: " + this.mBluetoothHeadsetDevice + " -> " + btDevice);
            BluetoothDevice previousActiveDevice = this.mBluetoothHeadsetDevice;
            if (!Objects.equals(btDevice, previousActiveDevice)) {
                if (!handleBtScoActiveDeviceChange(previousActiveDevice, false)) {
                    Log.w(TAG, "setBtScoActiveDevice() failed to remove previous device " + previousActiveDevice);
                }
                if (!handleBtScoActiveDeviceChange(btDevice, true)) {
                    Log.e(TAG, "setBtScoActiveDevice() failed to add new device " + btDevice);
                    btDevice = null;
                }
                this.mBluetoothHeadsetDevice = btDevice;
                if (this.mBluetoothHeadsetDevice == null) {
                    resetBluetoothSco();
                }
            }
        }
    }

    void disconnectAllBluetoothProfiles() {
        disconnectA2dp();
        disconnectA2dpSink();
        disconnectHeadset();
        disconnectHearingAid();
    }

    void disconnectA2dp() {
        synchronized (this.mConnectedDevices) {
            synchronized (this.mA2dpAvrcpLock) {
                ArraySet<String> toRemove = null;
                for (int i = 0; i < this.mConnectedDevices.size(); i++) {
                    DeviceListSpec deviceSpec = this.mConnectedDevices.valueAt(i);
                    if (deviceSpec.mDeviceType == 128) {
                        toRemove = toRemove != null ? toRemove : new ArraySet<>();
                        toRemove.add(deviceSpec.mDeviceAddress);
                    }
                }
                if (toRemove != null) {
                    int delay = checkSendBecomingNoisyIntent(128, 0, 0);
                    for (int i2 = 0; i2 < toRemove.size(); i2++) {
                        makeA2dpDeviceUnavailableLater(toRemove.valueAt(i2), delay);
                    }
                }
            }
        }
    }

    void disconnectA2dpSink() {
        synchronized (this.mConnectedDevices) {
            int i = 0;
            ArraySet<String> toRemove = null;
            for (int i2 = 0; i2 < this.mConnectedDevices.size(); i2++) {
                DeviceListSpec deviceSpec = this.mConnectedDevices.valueAt(i2);
                if (deviceSpec.mDeviceType == -2147352576) {
                    toRemove = toRemove != null ? toRemove : new ArraySet<>();
                    toRemove.add(deviceSpec.mDeviceAddress);
                }
            }
            if (toRemove != null) {
                while (true) {
                    int i3 = i;
                    int i4 = toRemove.size();
                    if (i3 >= i4) {
                        break;
                    }
                    makeA2dpSrcUnavailable(toRemove.valueAt(i3));
                    i = i3 + 1;
                }
            }
        }
    }

    void disconnectHeadset() {
        synchronized (this.mScoClients) {
            setBtScoActiveDevice(null);
            this.mBluetoothHeadset = null;
        }
    }

    void disconnectHearingAid() {
        synchronized (this.mConnectedDevices) {
            synchronized (this.mHearingAidLock) {
                ArraySet<String> toRemove = null;
                for (int i = 0; i < this.mConnectedDevices.size(); i++) {
                    DeviceListSpec deviceSpec = this.mConnectedDevices.valueAt(i);
                    if (deviceSpec.mDeviceType == 134217728) {
                        toRemove = toRemove != null ? toRemove : new ArraySet<>();
                        toRemove.add(deviceSpec.mDeviceAddress);
                    }
                }
                if (toRemove != null) {
                    checkSendBecomingNoisyIntent(134217728, 0, 0);
                    for (int i2 = 0; i2 < toRemove.size(); i2++) {
                        makeHearingAidDeviceUnavailable(toRemove.valueAt(i2));
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onCheckMusicActive(String caller) {
        synchronized (this.mSafeMediaVolumeState) {
            if (this.mSafeMediaVolumeState.intValue() == 2) {
                int device = getDeviceForStream(3);
                if ((67108876 & device) != 0) {
                    sendMsg(this.mAudioHandler, 14, 0, 0, 0, caller, MUSIC_ACTIVE_POLL_PERIOD_MS);
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
        this.mAudioHandler.obtainMessage(22, this.mMusicActiveMs, 0).sendToTarget();
    }

    private int getSafeUsbMediaVolumeIndex() {
        int min = MIN_STREAM_VOLUME[3];
        int max = MAX_STREAM_VOLUME[3];
        this.mSafeUsbMediaVolumeDbfs = this.mContext.getResources().getInteger(17694853) / 100.0f;
        while (true) {
            if (Math.abs(max - min) <= 1) {
                break;
            }
            int index = (max + min) / 2;
            float gainDB = AudioSystem.getStreamVolumeDB(3, index, 67108864);
            if (Float.isNaN(gainDB)) {
                break;
            } else if (gainDB == this.mSafeUsbMediaVolumeDbfs) {
                min = index;
                break;
            } else if (gainDB < this.mSafeUsbMediaVolumeDbfs) {
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
        synchronized (this.mSafeMediaVolumeState) {
            int mcc = this.mContext.getResources().getConfiguration().mcc;
            if (this.mMcc != mcc || (this.mMcc == 0 && force)) {
                this.mSafeMediaVolumeIndex = this.mContext.getResources().getInteger(17694852) * 10;
                this.mSafeUsbMediaVolumeIndex = getSafeUsbMediaVolumeIndex();
                if (!SystemProperties.getBoolean("audio.safemedia.force", false) && !this.mContext.getResources().getBoolean(17957018)) {
                    safeMediaVolumeEnabled = false;
                    boolean safeMediaVolumeBypass = SystemProperties.getBoolean("audio.safemedia.bypass", false);
                    if (!safeMediaVolumeEnabled && !safeMediaVolumeBypass) {
                        persistedState = 3;
                        if (this.mSafeMediaVolumeState.intValue() != 2) {
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
                    sendMsg(this.mAudioHandler, 18, 2, persistedState, 0, null, 0);
                }
                safeMediaVolumeEnabled = true;
                boolean safeMediaVolumeBypass2 = SystemProperties.getBoolean("audio.safemedia.bypass", false);
                if (!safeMediaVolumeEnabled) {
                }
                this.mSafeMediaVolumeState = 1;
                persistedState = 1;
                this.mMcc = mcc;
                sendMsg(this.mAudioHandler, 18, 2, persistedState, 0, null, 0);
            }
        }
    }

    private int checkForRingerModeChange(int oldIndex, int direction, int step, boolean isMuted, String caller, int flags) {
        int result = 1;
        if (isPlatformTelevision() || this.mIsSingleVolume) {
            return 1;
        }
        int ringerMode = getRingerModeInternal();
        switch (ringerMode) {
            case 0:
                if (this.mIsSingleVolume && direction == -1 && oldIndex >= 2 * step && isMuted) {
                    ringerMode = 2;
                } else if (direction == 1 || direction == 101 || direction == 100) {
                    if (!this.mVolumePolicy.volumeUpToExitSilent) {
                        result = 1 | 128;
                    } else {
                        ringerMode = (this.mHasVibrator && direction == 1) ? 1 : 2;
                    }
                }
                result &= -2;
                break;
            case 1:
                if (!this.mHasVibrator) {
                    Log.e(TAG, "checkForRingerModeChange() current ringer mode is vibratebut no vibrator is present");
                    break;
                } else {
                    if (direction == -1) {
                        if (this.mIsSingleVolume && oldIndex >= 2 * step && isMuted) {
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
                    break;
                }
            case 2:
                if (direction == -1) {
                    if (this.mHasVibrator) {
                        if (step <= oldIndex && oldIndex < 2 * step) {
                            ringerMode = 1;
                            this.mLoweredFromNormalToVibrateTime = SystemClock.uptimeMillis();
                            break;
                        }
                    } else if (oldIndex == step && this.mVolumePolicy.volumeDownToEnterSilent) {
                        ringerMode = 0;
                        break;
                    }
                } else if (this.mIsSingleVolume && (direction == 101 || direction == -100)) {
                    if (this.mHasVibrator) {
                        ringerMode = 1;
                    } else {
                        ringerMode = 0;
                    }
                    result = 1 & (-2);
                    break;
                }
                break;
            default:
                Log.e(TAG, "checkForRingerModeChange() wrong ringer mode: " + ringerMode);
                break;
        }
        if (isAndroidNPlus(caller) && wouldToggleZenMode(ringerMode) && !this.mNm.isNotificationPolicyAccessGrantedForPackage(caller) && (flags & 4096) == 0) {
            throw new SecurityException("Not allowed to change Do Not Disturb state");
        }
        setRingerMode(ringerMode, "AudioService.checkForRingerModeChange", false);
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
        NotificationManager.Policy zenPolicy = this.mNm.getNotificationPolicy();
        boolean muteAlarms = (zenPolicy.priorityCategories & 32) == 0;
        boolean muteMedia = (zenPolicy.priorityCategories & 64) == 0;
        boolean muteSystem = (zenPolicy.priorityCategories & 128) == 0;
        boolean muteNotificationAndRing = ZenModeConfig.areAllPriorityOnlyNotificationZenSoundsMuted(this.mNm.getNotificationPolicy());
        return (muteAlarms && isAlarm(streamType)) || (muteMedia && isMedia(streamType)) || ((muteSystem && isSystem(streamType)) || (muteNotificationAndRing && isNotificationOrRinger(streamType)));
    }

    private boolean isStreamMutedByRingerOrZenMode(int streamType) {
        return (this.mRingerAndZenModeMutedStreams & (1 << streamType)) != 0;
    }

    private boolean updateZenModeAffectedStreams() {
        int zenModeAffectedStreams = 0;
        if (this.mSystemReady && this.mNm.getZenMode() == 1) {
            NotificationManager.Policy zenPolicy = this.mNm.getNotificationPolicy();
            if ((zenPolicy.priorityCategories & 32) == 0) {
                zenModeAffectedStreams = 0 | 16;
            }
            if ((zenPolicy.priorityCategories & 64) == 0) {
                zenModeAffectedStreams |= 8;
            }
            if ((zenPolicy.priorityCategories & 128) == 0) {
                zenModeAffectedStreams |= 2;
            }
        }
        if (this.mZenModeAffectedStreams != zenModeAffectedStreams) {
            this.mZenModeAffectedStreams = zenModeAffectedStreams;
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mSettingsLock")
    public boolean updateRingerAndZenModeAffectedStreams() {
        int ringerModeAffectedStreams;
        int ringerModeAffectedStreams2;
        boolean updatedZenModeAffectedStreams = updateZenModeAffectedStreams();
        int ringerModeAffectedStreams3 = Settings.System.getIntForUser(this.mContentResolver, "mode_ringer_streams_affected", 166, -2);
        if (this.mIsSingleVolume) {
            ringerModeAffectedStreams3 = 0;
        } else if (this.mRingerModeDelegate != null) {
            ringerModeAffectedStreams3 = this.mRingerModeDelegate.getRingerModeAffectedStreams(ringerModeAffectedStreams3);
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
        if (direction != -100) {
            switch (direction) {
                case -1:
                case 0:
                case 1:
                    return;
                default:
                    switch (direction) {
                        case 100:
                        case 101:
                            return;
                        default:
                            throw new IllegalArgumentException("Bad direction " + direction);
                    }
            }
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

    private boolean isInCommunication() {
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
        if (this.mPlatformType == 1) {
            if ((isBtCallOn() && getBtCallMode() == 1) || getBtCallOnFlag() == 3) {
                Log.d(TAG, "getActiveStreamType: is in BT Call");
                return 6;
            } else if (suggestedStreamType == Integer.MIN_VALUE) {
                if (wasStreamActiveRecently(10, sStreamOverrideDelayMs)) {
                    if (DEBUG_VOL) {
                        Log.v(TAG, "getActiveStreamType: Forcing STREAM_ACCESSIBILITY stream active (XUI Policy)");
                    }
                    return 10;
                } else if (wasStreamActiveRecently(9, sStreamOverrideDelayMs)) {
                    if (DEBUG_VOL) {
                        Log.v(TAG, "getActiveStreamType: Forcing STREAM_TTS stream active (XUI Policy)");
                    }
                    return 9;
                } else {
                    if (DEBUG_VOL) {
                        Log.v(TAG, "getActiveStreamType: Forcing DEFAULT_VOL_STREAM_NO_PLAYBACK(3) b/c default");
                    }
                    return 3;
                }
            } else if (wasStreamActiveRecently(2, sStreamOverrideDelayMs)) {
                if (DEBUG_VOL) {
                    Log.v(TAG, "getActiveStreamType: Forcing STREAM_RING stream active");
                }
                return 2;
            }
        }
        if (mXuiVolPolicy == 1 && mActiveStreamStatus && mCurActiveStream != -1) {
            Log.d(TAG, "getActiveStreamType: Stream Locked mode - curActiveStr is " + mCurActiveStream);
            return mCurActiveStream;
        } else if ((isBtCallOn() && getBtCallMode() == 1) || getBtCallOnFlag() == 3) {
            Log.d(TAG, "getActiveStreamType: is in BT Call");
            return 6;
        } else if (suggestedStreamType == Integer.MIN_VALUE) {
            if (mXuiVolPolicy != 2 && AudioSystem.isStreamActive(9, sStreamOverrideDelayMs)) {
                if (DEBUG_VOL) {
                    Log.v(TAG, "getActiveStreamType: Forcing STREAM_TTS");
                }
                return 9;
            } else if (mXuiVolPolicy != 2 && AudioSystem.isStreamActive(10, sStreamOverrideDelayMs)) {
                if (DEBUG_VOL) {
                    Log.v(TAG, "getActiveStreamType: Forcing STREAM_ACCESSIBILITY");
                }
                return 10;
            } else {
                if (DEBUG_VOL) {
                    Log.v(TAG, "getActiveStreamType: Forcing DEFAULT_VOL_STREAM_NO_PLAYBACK(3) b/c default");
                }
                return 3;
            }
        } else {
            if (DEBUG_VOL) {
                Log.v(TAG, "getActiveStreamType: Returning suggested type " + suggestedStreamType);
            }
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
        synchronized (mLastDeviceConnectMsgTime) {
            long time = SystemClock.uptimeMillis() + delay;
            if (msg == 101 || msg == 102 || msg == 105 || msg == 100 || msg == 103 || msg == 106) {
                if (mLastDeviceConnectMsgTime.longValue() >= time) {
                    time = mLastDeviceConnectMsgTime.longValue() + 30;
                }
                mLastDeviceConnectMsgTime = Long.valueOf(time);
            }
            handler.sendMessageAtTime(handler.obtainMessage(msg, arg1, arg2, obj), time);
        }
    }

    boolean checkAudioSettingsPermission(String method) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.MODIFY_AUDIO_SETTINGS") == 0) {
            return true;
        }
        String msg = "Audio Settings Permission Denial: " + method + " from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid();
        Log.w(TAG, msg);
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getDeviceForStream(int stream) {
        int device = getDevicesForStream(stream);
        if (((device - 1) & device) != 0) {
            if ((device & 2) != 0) {
                return 2;
            }
            if ((262144 & device) != 0) {
                return DumpState.DUMP_DOMAIN_PREFERRED;
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

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class WiredDeviceConnectionState {
        public final String mAddress;
        public final String mCaller;
        public final String mName;
        public final int mState;
        public final int mType;

        public WiredDeviceConnectionState(int type, int state, String address, String name, String caller) {
            this.mType = type;
            this.mState = state;
            this.mAddress = address;
            this.mName = name;
            this.mCaller = caller;
        }
    }

    public void setWiredDeviceConnectionState(int type, int state, String address, String name, String caller) {
        StringBuilder sb;
        String str;
        String str2;
        synchronized (this.mConnectedDevices) {
            try {
                try {
                    if (DEBUG_DEVICES) {
                        try {
                            sb = new StringBuilder();
                            sb.append("setWiredDeviceConnectionState(");
                            sb.append(state);
                            sb.append(" nm: ");
                            str = name;
                        } catch (Throwable th) {
                            th = th;
                        }
                        try {
                            sb.append(str);
                            sb.append(" addr:");
                            str2 = address;
                            sb.append(str2);
                            sb.append(")");
                            Slog.i(TAG, sb.toString());
                        } catch (Throwable th2) {
                            th = th2;
                            throw th;
                        }
                    } else {
                        str2 = address;
                        str = name;
                    }
                    int delay = checkSendBecomingNoisyIntent(type, state, 0);
                    queueMsgUnderWakeLock(this.mAudioHandler, 100, 0, 0, new WiredDeviceConnectionState(type, state, str2, str, caller), delay);
                } catch (Throwable th3) {
                    th = th3;
                }
            } catch (Throwable th4) {
                th = th4;
            }
        }
    }

    public int setBluetoothHearingAidDeviceConnectionState(BluetoothDevice device, int state, boolean suppressNoisyIntent, int musicDevice) {
        int intState;
        synchronized (this.mConnectedDevices) {
            if (!suppressNoisyIntent) {
                intState = state == 2 ? 1 : 0;
                intState = checkSendBecomingNoisyIntent(134217728, intState, musicDevice);
            }
            queueMsgUnderWakeLock(this.mAudioHandler, 105, state, 0, device, intState);
        }
        return intState;
    }

    public int setBluetoothA2dpDeviceConnectionState(BluetoothDevice device, int state, int profile) {
        return setBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(device, state, profile, false, -1);
    }

    public int setBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(BluetoothDevice device, int state, int profile, boolean suppressNoisyIntent, int a2dpVolume) {
        AudioEventLogger audioEventLogger = this.mDeviceLogger;
        audioEventLogger.log(new AudioEventLogger.StringEvent("setBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent state=" + state + " addr=" + device.getAddress() + " prof=" + profile + " supprNoisy=" + suppressNoisyIntent + " vol=" + a2dpVolume));
        if (this.mAudioHandler.hasMessages(102, device)) {
            this.mDeviceLogger.log(new AudioEventLogger.StringEvent("A2DP connection state ignored"));
            return 0;
        }
        return setBluetoothA2dpDeviceConnectionStateInt(device, state, profile, suppressNoisyIntent, 0, a2dpVolume);
    }

    public int setBluetoothA2dpDeviceConnectionStateInt(BluetoothDevice device, int state, int profile, boolean suppressNoisyIntent, int musicDevice, int a2dpVolume) {
        BluetoothDevice bluetoothDevice;
        if (profile != 2 && profile != 11) {
            throw new IllegalArgumentException("invalid profile " + profile);
        }
        synchronized (this.mConnectedDevices) {
            try {
                if (profile == 2 && !suppressNoisyIntent) {
                    intState = state == 2 ? 1 : 0;
                    try {
                        intState = checkSendBecomingNoisyIntent(128, intState, musicDevice);
                    } catch (Throwable th) {
                        th = th;
                        throw th;
                    }
                }
                int delay = intState;
                if (DEBUG_DEVICES) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("setBluetoothA2dpDeviceConnectionStateInt device: ");
                    bluetoothDevice = device;
                    sb.append(bluetoothDevice);
                    sb.append(" state: ");
                    sb.append(state);
                    sb.append(" delay(ms): ");
                    sb.append(delay);
                    sb.append(" suppressNoisyIntent: ");
                    sb.append(suppressNoisyIntent);
                    Log.d(TAG, sb.toString());
                } else {
                    bluetoothDevice = device;
                }
                queueMsgUnderWakeLock(this.mAudioHandler, profile == 2 ? 102 : 101, state, a2dpVolume, bluetoothDevice, delay);
                return delay;
            } catch (Throwable th2) {
                th = th2;
            }
        }
    }

    public void handleBluetoothA2dpDeviceConfigChange(BluetoothDevice device) {
        synchronized (this.mConnectedDevices) {
            queueMsgUnderWakeLock(this.mAudioHandler, 103, 0, 0, device, 0);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onAccessoryPlugMediaUnmute(int newDevice) {
        if (DEBUG_VOL) {
            Log.i(TAG, String.format("onAccessoryPlugMediaUnmute newDevice=%d [%s]", Integer.valueOf(newDevice), AudioSystem.getOutputDeviceName(newDevice)));
        }
        synchronized (this.mConnectedDevices) {
            if (this.mNm.getZenMode() != 2 && (DEVICE_MEDIA_UNMUTED_ON_PLUG & newDevice) != 0 && this.mStreamStates[3].mIsMuted && this.mStreamStates[3].getIndex(newDevice) != 0 && (AudioSystem.getDevicesForStream(3) & newDevice) != 0) {
                if (DEBUG_VOL) {
                    Log.i(TAG, String.format(" onAccessoryPlugMediaUnmute unmuting device=%d [%s]", Integer.valueOf(newDevice), AudioSystem.getOutputDeviceName(newDevice)));
                }
                this.mStreamStates[3].mute(false);
            }
        }
    }

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
            if (AudioService.mStreamVolumeAlias[this.mStreamType] == this.mStreamType) {
                EventLogTags.writeStreamDevicesChanged(this.mStreamType, prevDevices, devices);
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
            return (this.mVolumeIndexSettingName == null || this.mVolumeIndexSettingName.isEmpty()) ? false : true;
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
                                        this.mIndexMap.put(device, getValidIndex(10 * index));
                                    }
                                }
                                i++;
                            }
                        }
                        return;
                    }
                    int index2 = 10 * AudioSystem.DEFAULT_STREAM_VOLUME[this.mStreamType];
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
            if (index == 1) {
                return ((int) (this.mIndexMax * 0.5d)) / 10;
            }
            if (index == 2) {
                return ((int) (this.mIndexMax * 0.7d)) / 10;
            }
            if (index == 3) {
                return ((int) (this.mIndexMax * 0.85d)) / 10;
            }
            return (this.mIndexMax + 5) / 10;
        }

        public void applyDeviceVolume_syncVSS(int device) {
            int index;
            if (this.mIsMuted) {
                index = 0;
            } else {
                int index2 = device & 896;
                if (index2 != 0 && AudioService.this.mAvrcpAbsVolSupported) {
                    index = getAbsoluteVolumeIndex((getIndex(device) + 5) / 10);
                } else if ((AudioService.this.mFullVolumeDevices & device) != 0) {
                    index = (this.mIndexMax + 5) / 10;
                } else if ((134217728 & device) != 0) {
                    index = (this.mIndexMax + 5) / 10;
                } else {
                    index = (getIndex(device) + 5) / 10;
                }
            }
            AudioSystem.setStreamVolumeIndex(this.mStreamType, index, device);
        }

        public void applyAllVolumes() {
            int index;
            int index2;
            synchronized (VolumeStreamState.class) {
                for (int i = 0; i < this.mIndexMap.size(); i++) {
                    int device = this.mIndexMap.keyAt(i);
                    if (device != 1073741824) {
                        if (this.mIsMuted) {
                            index2 = 0;
                        } else {
                            int index3 = device & 896;
                            if (index3 != 0 && AudioService.this.mAvrcpAbsVolSupported) {
                                index2 = getAbsoluteVolumeIndex((getIndex(device) + 5) / 10);
                            } else if ((AudioService.this.mFullVolumeDevices & device) != 0) {
                                index2 = (this.mIndexMax + 5) / 10;
                            } else if ((134217728 & device) != 0) {
                                index2 = (this.mIndexMax + 5) / 10;
                            } else {
                                index2 = (this.mIndexMap.valueAt(i) + 5) / 10;
                                AudioSystem.setStreamVolumeIndex(this.mStreamType, index2, device);
                            }
                        }
                        AudioSystem.setStreamVolumeIndex(this.mStreamType, index2, device);
                    }
                }
                if (this.mIsMuted) {
                    index = 0;
                } else {
                    int index4 = getIndex(1073741824);
                    index = (index4 + 5) / 10;
                }
                AudioSystem.setStreamVolumeIndex(this.mStreamType, index, 1073741824);
            }
        }

        public boolean adjustIndex(int deltaIndex, int device, String caller) {
            return setIndex(getIndex(device) + deltaIndex, device, caller);
        }

        public boolean setIndex(int index, int device, String caller) {
            int oldIndex;
            int index2;
            boolean changed;
            boolean changed2;
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
                            if ((otherDevice & 112) != 0) {
                                this.mIndexMap.put(otherDevice, index2);
                            }
                        }
                    }
                }
                changed2 = changed;
            }
            if (changed2) {
                int oldIndex2 = (oldIndex + 5) / 10;
                int index3 = (index2 + 5) / 10;
                if (AudioService.mStreamVolumeAlias[this.mStreamType] == this.mStreamType) {
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
            return changed2;
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

        @GuardedBy("VolumeStreamState.class")
        public void refreshRange(int sourceStreamType) {
            this.mIndexMin = AudioService.MIN_STREAM_VOLUME[sourceStreamType] * 10;
            this.mIndexMax = AudioService.MAX_STREAM_VOLUME[sourceStreamType] * 10;
            for (int i = 0; i < this.mIndexMap.size(); i++) {
                int device = this.mIndexMap.keyAt(i);
                int index = this.mIndexMap.valueAt(i);
                this.mIndexMap.put(device, getValidIndex(index));
            }
        }

        @GuardedBy("VolumeStreamState.class")
        public void setAllIndexes(VolumeStreamState srcStream, String caller) {
            if (this.mStreamType == srcStream.mStreamType) {
                return;
            }
            int srcStreamType = srcStream.getStreamType();
            int index = srcStream.getIndex(1073741824);
            int index2 = AudioService.this.rescaleIndex(index, srcStreamType, this.mStreamType);
            for (int i = 0; i < this.mIndexMap.size(); i++) {
                this.mIndexMap.put(this.mIndexMap.keyAt(i), index2);
            }
            SparseIntArray srcMap = srcStream.mIndexMap;
            for (int i2 = 0; i2 < srcMap.size(); i2++) {
                int device = srcMap.keyAt(i2);
                int index3 = srcMap.valueAt(i2);
                setIndex(AudioService.this.rescaleIndex(index3, srcStreamType, this.mStreamType), device, caller);
            }
        }

        @GuardedBy("VolumeStreamState.class")
        public void setAllIndexesToMax() {
            for (int i = 0; i < this.mIndexMap.size(); i++) {
                this.mIndexMap.put(this.mIndexMap.keyAt(i), this.mIndexMax);
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
            synchronized (VolumeStreamState.class) {
                if (AudioService.mStreamVolumeAlias[this.mStreamType] == 3) {
                    for (int i = 0; i < this.mIndexMap.size(); i++) {
                        int device = this.mIndexMap.keyAt(i);
                        int index = this.mIndexMap.valueAt(i);
                        if ((AudioService.this.mFullVolumeDevices & device) != 0 || ((AudioService.this.mFixedVolumeDevices & device) != 0 && index != 0)) {
                            this.mIndexMap.put(device, this.mIndexMax);
                        }
                        applyDeviceVolume_syncVSS(device);
                    }
                }
            }
        }

        private int getValidIndex(int index) {
            if (index >= this.mIndexMin) {
                if (AudioService.this.mUseFixedVolume || index > this.mIndexMax) {
                    return this.mIndexMax;
                }
                return index;
            }
            return this.mIndexMin;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void dump(PrintWriter pw) {
            pw.print("   Muted: ");
            pw.println(this.mIsMuted);
            pw.print("   Min: ");
            pw.println((this.mIndexMin + 5) / 10);
            pw.print("   Max: ");
            pw.println((this.mIndexMax + 5) / 10);
            pw.print("   Current: ");
            int n = 0;
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
            super(AudioService.TAG);
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
    public void setDeviceVolume(VolumeStreamState streamState, int device) {
        synchronized (VolumeStreamState.class) {
            streamState.applyDeviceVolume_syncVSS(device);
            int numStreamTypes = AudioSystem.getNumStreamTypes();
            for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                if (streamType != streamState.mStreamType && mStreamVolumeAlias[streamType] == streamState.mStreamType) {
                    int streamDevice = getDeviceForStream(streamType);
                    if (device != streamDevice && this.mAvrcpAbsVolSupported && (device & 896) != 0) {
                        this.mStreamStates[streamType].applyDeviceVolume_syncVSS(device);
                    }
                    this.mStreamStates[streamType].applyDeviceVolume_syncVSS(streamDevice);
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
            String filePath = Environment.getProductDirectory() + AudioService.SOUND_EFFECTS_PATH + ((String) AudioService.SOUND_EFFECT_FILES.get(AudioService.this.SOUND_EFFECT_FILES_MAP[effectType][0]));
            if (!new File(filePath).isFile()) {
                return Environment.getRootDirectory() + AudioService.SOUND_EFFECTS_PATH + ((String) AudioService.SOUND_EFFECT_FILES.get(AudioService.this.SOUND_EFFECT_FILES_MAP[effectType][0]));
            }
            return filePath;
        }

        private boolean onLoadSoundEffects() {
            int i;
            int status;
            synchronized (AudioService.this.mSoundEffectsLock) {
                if (!AudioService.this.mSystemReady) {
                    Log.w(AudioService.TAG, "onLoadSoundEffects() called before boot complete");
                    return false;
                } else if (AudioService.this.mSoundPool != null) {
                    return true;
                } else {
                    AudioService.this.loadTouchSoundAssets();
                    AudioService.this.mSoundPool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder().setUsage(5).setContentType(4).build()).build();
                    AudioService.this.mSoundPoolCallBack = null;
                    AudioService.this.mSoundPoolListenerThread = new SoundPoolListenerThread();
                    AudioService.this.mSoundPoolListenerThread.start();
                    int attempts = 3;
                    while (true) {
                        if (AudioService.this.mSoundPoolCallBack != null) {
                            break;
                        }
                        int attempts2 = attempts - 1;
                        if (attempts <= 0) {
                            attempts = attempts2;
                            break;
                        }
                        try {
                            AudioService.this.mSoundEffectsLock.wait(5000L);
                        } catch (InterruptedException e) {
                            Log.w(AudioService.TAG, "Interrupted while waiting sound pool listener thread.");
                        }
                        attempts = attempts2;
                    }
                    if (AudioService.this.mSoundPoolCallBack == null) {
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
                    int[] poolId = new int[AudioService.SOUND_EFFECT_FILES.size()];
                    int fileIdx = 0;
                    while (true) {
                        i = -1;
                        if (fileIdx >= AudioService.SOUND_EFFECT_FILES.size()) {
                            break;
                        }
                        poolId[fileIdx] = -1;
                        fileIdx++;
                    }
                    int numSamples = 0;
                    int numSamples2 = 0;
                    while (numSamples2 < 18) {
                        if (AudioService.this.SOUND_EFFECT_FILES_MAP[numSamples2][1] != 0) {
                            if (poolId[AudioService.this.SOUND_EFFECT_FILES_MAP[numSamples2][0]] == i) {
                                String filePath = getSoundEffectFilePath(numSamples2);
                                int sampleId = AudioService.this.mSoundPool.load(filePath, 0);
                                if (sampleId <= 0) {
                                    Log.w(AudioService.TAG, "Soundpool could not load file: " + filePath);
                                } else {
                                    AudioService.this.SOUND_EFFECT_FILES_MAP[numSamples2][1] = sampleId;
                                    poolId[AudioService.this.SOUND_EFFECT_FILES_MAP[numSamples2][0]] = sampleId;
                                    numSamples++;
                                }
                            } else {
                                AudioService.this.SOUND_EFFECT_FILES_MAP[numSamples2][1] = poolId[AudioService.this.SOUND_EFFECT_FILES_MAP[numSamples2][0]];
                            }
                        }
                        numSamples2++;
                        i = -1;
                    }
                    if (numSamples > 0) {
                        AudioService.this.mSoundPoolCallBack.setSamples(poolId);
                        int attempts3 = 3;
                        int status2 = 1;
                        while (true) {
                            status = status2;
                            if (status != 1) {
                                break;
                            }
                            int attempts4 = attempts3 - 1;
                            if (attempts3 <= 0) {
                                break;
                            }
                            try {
                                AudioService.this.mSoundEffectsLock.wait(5000L);
                                status2 = AudioService.this.mSoundPoolCallBack.status();
                            } catch (InterruptedException e2) {
                                Log.w(AudioService.TAG, "Interrupted while waiting sound pool callback.");
                                status2 = status;
                            }
                            attempts3 = attempts4;
                        }
                    } else {
                        status = -1;
                    }
                    int attempts5 = status;
                    if (AudioService.this.mSoundPoolLooper != null) {
                        AudioService.this.mSoundPoolLooper.quit();
                        AudioService.this.mSoundPoolLooper = null;
                    }
                    AudioService.this.mSoundPoolListenerThread = null;
                    if (attempts5 != 0) {
                        Log.w(AudioService.TAG, "onLoadSoundEffects(), Error " + attempts5 + " while loading samples");
                        for (int effect = 0; effect < 18; effect++) {
                            if (AudioService.this.SOUND_EFFECT_FILES_MAP[effect][1] > 0) {
                                AudioService.this.SOUND_EFFECT_FILES_MAP[effect][1] = -1;
                            }
                        }
                        AudioService.this.mSoundPool.release();
                        AudioService.this.mSoundPool = null;
                    }
                    return attempts5 == 0;
                }
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
                for (int effect = 0; effect < 18; effect++) {
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
                        } catch (IOException ex) {
                            Log.w(AudioService.TAG, "MediaPlayer IOException: " + ex);
                        }
                    } catch (IllegalArgumentException ex2) {
                        Log.w(AudioService.TAG, "MediaPlayer IllegalArgumentException: " + ex2);
                    } catch (IllegalStateException ex3) {
                        Log.w(AudioService.TAG, "MediaPlayer IllegalStateException: " + ex3);
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

        private void setForceUse(int usage, int config, String eventSource) {
            synchronized (AudioService.this.mConnectedDevices) {
                AudioService.this.setForceUseInt_SyncDevices(usage, config, eventSource);
            }
        }

        private void onPersistSafeVolumeState(int state) {
            Settings.Global.putInt(AudioService.this.mContentResolver, "audio_safe_volume_state", state);
        }

        private void onNotifyVolumeEvent(IAudioPolicyCallback apc, int direction) {
            try {
                apc.notifyVolumeAdjust(direction);
            } catch (Exception e) {
            }
        }

        /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            AudioRoutesInfo routes;
            int i = msg.what;
            switch (i) {
                case 0:
                    AudioService.this.setDeviceVolume((VolumeStreamState) msg.obj, msg.arg1);
                    return;
                case 1:
                    persistVolume((VolumeStreamState) msg.obj, msg.arg1);
                    return;
                default:
                    switch (i) {
                        case 3:
                            persistRingerMode(AudioService.this.getRingerModeInternal());
                            return;
                        case 4:
                            AudioService.this.onAudioServerDied();
                            return;
                        case 5:
                            onPlaySoundEffect(msg.arg1, msg.arg2);
                            return;
                        default:
                            switch (i) {
                                case 7:
                                    boolean loaded = onLoadSoundEffects();
                                    if (msg.obj != null) {
                                        LoadSoundEffectReply reply = (LoadSoundEffectReply) msg.obj;
                                        synchronized (reply) {
                                            reply.mStatus = loaded ? 0 : -1;
                                            reply.notify();
                                        }
                                        return;
                                    }
                                    return;
                                case 8:
                                    break;
                                case 9:
                                    AudioService.this.resetBluetoothSco();
                                    return;
                                case 10:
                                    setAllVolumes((VolumeStreamState) msg.obj);
                                    return;
                                default:
                                    switch (i) {
                                        case 12:
                                            int N = AudioService.this.mRoutesObservers.beginBroadcast();
                                            if (N > 0) {
                                                synchronized (AudioService.this.mCurAudioRoutes) {
                                                    routes = new AudioRoutesInfo(AudioService.this.mCurAudioRoutes);
                                                }
                                                while (N > 0) {
                                                    N--;
                                                    IAudioRoutesObserver obs = AudioService.this.mRoutesObservers.getBroadcastItem(N);
                                                    try {
                                                        obs.dispatchAudioRoutesChanged(routes);
                                                    } catch (RemoteException e) {
                                                    }
                                                }
                                            }
                                            AudioService.this.mRoutesObservers.finishBroadcast();
                                            AudioService.this.observeDevicesForStreams(-1);
                                            return;
                                        case 13:
                                            break;
                                        case 14:
                                            AudioService.this.onCheckMusicActive((String) msg.obj);
                                            return;
                                        case 15:
                                            AudioService.this.onSendBecomingNoisyIntent();
                                            return;
                                        case 16:
                                        case 17:
                                            AudioService.this.onConfigureSafeVolume(msg.what == 17, (String) msg.obj);
                                            return;
                                        case 18:
                                            onPersistSafeVolumeState(msg.arg1);
                                            return;
                                        case 19:
                                            AudioService.this.onBroadcastScoConnectionState(msg.arg1);
                                            return;
                                        case 20:
                                            onUnloadSoundEffects();
                                            return;
                                        case 21:
                                            AudioService.this.onSystemReady();
                                            return;
                                        case 22:
                                            int musicActiveMs = msg.arg1;
                                            Settings.Secure.putIntForUser(AudioService.this.mContentResolver, "unsafe_volume_music_active_ms", musicActiveMs, -2);
                                            return;
                                        default:
                                            switch (i) {
                                                case 24:
                                                    AudioService.this.onUnmuteStream(msg.arg1, msg.arg2);
                                                    return;
                                                case 25:
                                                    AudioService.this.onDynPolicyMixStateUpdate((String) msg.obj, msg.arg1);
                                                    return;
                                                case 26:
                                                    AudioService.this.onIndicateSystemReady();
                                                    return;
                                                case AudioService.MSG_ACCESSORY_PLUG_MEDIA_UNMUTE /* 27 */:
                                                    AudioService.this.onAccessoryPlugMediaUnmute(msg.arg1);
                                                    return;
                                                case 28:
                                                    onNotifyVolumeEvent((IAudioPolicyCallback) msg.obj, msg.arg1);
                                                    return;
                                                case 29:
                                                    AudioService.this.onDispatchAudioServerStateChange(msg.arg1 == 1);
                                                    return;
                                                case 30:
                                                    AudioService.this.onEnableSurroundFormats((ArrayList) msg.obj);
                                                    return;
                                                default:
                                                    switch (i) {
                                                        case 100:
                                                            WiredDeviceConnectionState connectState = (WiredDeviceConnectionState) msg.obj;
                                                            AudioService.this.mDeviceLogger.log(new AudioServiceEvents.WiredDevConnectEvent(connectState));
                                                            AudioService.this.onSetWiredDeviceConnectionState(connectState.mType, connectState.mState, connectState.mAddress, connectState.mName, connectState.mCaller);
                                                            AudioService.this.mAudioEventWakeLock.release();
                                                            return;
                                                        case 101:
                                                            AudioService.this.onSetA2dpSourceConnectionState((BluetoothDevice) msg.obj, msg.arg1);
                                                            AudioService.this.mAudioEventWakeLock.release();
                                                            return;
                                                        case 102:
                                                            AudioService.this.onSetA2dpSinkConnectionState((BluetoothDevice) msg.obj, msg.arg1, msg.arg2);
                                                            AudioService.this.mAudioEventWakeLock.release();
                                                            return;
                                                        case 103:
                                                            AudioService.this.onBluetoothA2dpDeviceConfigChange((BluetoothDevice) msg.obj);
                                                            AudioService.this.mAudioEventWakeLock.release();
                                                            return;
                                                        case 104:
                                                            AudioService.this.mPlaybackMonitor.disableAudioForUid(msg.arg1 == 1, msg.arg2);
                                                            AudioService.this.mAudioEventWakeLock.release();
                                                            return;
                                                        case 105:
                                                            AudioService.this.onSetHearingAidConnectionState((BluetoothDevice) msg.obj, msg.arg1);
                                                            AudioService.this.mAudioEventWakeLock.release();
                                                            return;
                                                        case 106:
                                                            synchronized (AudioService.this.mConnectedDevices) {
                                                                AudioService.this.makeA2dpDeviceUnavailableNow((String) msg.obj);
                                                            }
                                                            AudioService.this.mAudioEventWakeLock.release();
                                                            return;
                                                        default:
                                                            return;
                                                    }
                                            }
                                    }
                            }
                            setForceUse(msg.arg1, msg.arg2, (String) msg.obj);
                            return;
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
            AudioService.this.mEncodedSurroundMode = Settings.Global.getInt(AudioService.this.mContentResolver, "encoded_surround_output", 0);
            AudioService.this.mContentResolver.registerContentObserver(Settings.Global.getUriFor("encoded_surround_output"), false, this);
            AudioService.this.mEnabledSurroundFormats = Settings.Global.getString(AudioService.this.mContentResolver, "encoded_surround_output_enabled_formats");
            AudioService.this.mContentResolver.registerContentObserver(Settings.Global.getUriFor("encoded_surround_output_enabled_formats"), false, this);
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
                updateEncodedSurroundOutput();
                AudioService.this.sendEnabledSurroundFormats(AudioService.this.mContentResolver, AudioService.this.mSurroundModeChanged);
            }
        }

        private void updateEncodedSurroundOutput() {
            int newSurroundMode = Settings.Global.getInt(AudioService.this.mContentResolver, "encoded_surround_output", 0);
            if (AudioService.this.mEncodedSurroundMode != newSurroundMode) {
                AudioService.this.sendEncodedSurroundMode(newSurroundMode, "SettingsObserver");
                synchronized (AudioService.this.mConnectedDevices) {
                    String key = AudioService.this.makeDeviceListKey(1024, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                    DeviceListSpec deviceSpec = (DeviceListSpec) AudioService.this.mConnectedDevices.get(key);
                    if (deviceSpec != null) {
                        AudioService.this.setWiredDeviceConnectionState(1024, 0, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, PackageManagerService.PLATFORM_PACKAGE_NAME);
                        AudioService.this.setWiredDeviceConnectionState(1024, 1, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, PackageManagerService.PLATFORM_PACKAGE_NAME);
                    }
                }
                AudioService.this.mEncodedSurroundMode = newSurroundMode;
                AudioService.this.mSurroundModeChanged = true;
                return;
            }
            AudioService.this.mSurroundModeChanged = false;
        }
    }

    private void makeA2dpDeviceAvailable(String address, String name, String eventSource) {
        VolumeStreamState volumeStreamState = this.mStreamStates[3];
        setBluetoothA2dpOnInt(true, eventSource);
        AudioSystem.setDeviceConnectionState(128, 1, address, name);
        AudioSystem.setParameters("A2dpSuspended=false");
        this.mConnectedDevices.put(makeDeviceListKey(128, address), new DeviceListSpec(128, name, address));
        sendMsg(this.mAudioHandler, MSG_ACCESSORY_PLUG_MEDIA_UNMUTE, 2, 128, 0, null, 0);
        setCurrentAudioRouteNameIfPossible(name);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onSendBecomingNoisyIntent() {
        sendBroadcastToAll(new Intent("android.media.AUDIO_BECOMING_NOISY"));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void makeA2dpDeviceUnavailableNow(String address) {
        if (address == null) {
            return;
        }
        synchronized (this.mA2dpAvrcpLock) {
            this.mAvrcpAbsVolSupported = false;
        }
        AudioSystem.setDeviceConnectionState(128, 0, address, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        this.mConnectedDevices.remove(makeDeviceListKey(128, address));
        setCurrentAudioRouteNameIfPossible(null);
        if (this.mDockAddress == address) {
            this.mDockAddress = null;
        }
    }

    private void makeA2dpDeviceUnavailableLater(String address, int delayMs) {
        AudioSystem.setParameters("A2dpSuspended=true");
        this.mConnectedDevices.remove(makeDeviceListKey(128, address));
        queueMsgUnderWakeLock(this.mAudioHandler, 106, 0, 0, address, delayMs);
    }

    private void makeA2dpSrcAvailable(String address) {
        AudioSystem.setDeviceConnectionState(-2147352576, 1, address, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        this.mConnectedDevices.put(makeDeviceListKey(-2147352576, address), new DeviceListSpec(-2147352576, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, address));
    }

    private void makeA2dpSrcUnavailable(String address) {
        AudioSystem.setDeviceConnectionState(-2147352576, 0, address, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        this.mConnectedDevices.remove(makeDeviceListKey(-2147352576, address));
    }

    private void setHearingAidVolume(int index, int streamType) {
        synchronized (this.mHearingAidLock) {
            if (this.mHearingAid != null) {
                int gainDB = (int) AudioSystem.getStreamVolumeDB(streamType, index / 10, 134217728);
                if (gainDB < -128) {
                    gainDB = -128;
                }
                this.mHearingAid.setVolume(gainDB);
            }
        }
    }

    private void makeHearingAidDeviceAvailable(String address, String name, String eventSource) {
        int index = this.mStreamStates[3].getIndex(134217728);
        setHearingAidVolume(index, 3);
        AudioSystem.setDeviceConnectionState(134217728, 1, address, name);
        this.mConnectedDevices.put(makeDeviceListKey(134217728, address), new DeviceListSpec(134217728, name, address));
        sendMsg(this.mAudioHandler, MSG_ACCESSORY_PLUG_MEDIA_UNMUTE, 2, 134217728, 0, null, 0);
        sendMsg(this.mAudioHandler, 0, 2, 134217728, 0, this.mStreamStates[3], 0);
        setCurrentAudioRouteNameIfPossible(name);
    }

    private void makeHearingAidDeviceUnavailable(String address) {
        AudioSystem.setDeviceConnectionState(134217728, 0, address, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        this.mConnectedDevices.remove(makeDeviceListKey(134217728, address));
        setCurrentAudioRouteNameIfPossible(null);
    }

    private void cancelA2dpDeviceTimeout() {
        this.mAudioHandler.removeMessages(106);
    }

    private boolean hasScheduledA2dpDockTimeout() {
        return this.mAudioHandler.hasMessages(106);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onSetA2dpSinkConnectionState(BluetoothDevice btDevice, int state, int a2dpVolume) {
        if (DEBUG_DEVICES) {
            Log.d(TAG, "onSetA2dpSinkConnectionState btDevice= " + btDevice + " state= " + state + " is dock: " + btDevice.isBluetoothDock());
        }
        if (btDevice == null) {
            return;
        }
        String address = btDevice.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
        synchronized (this.mConnectedDevices) {
            String key = makeDeviceListKey(128, btDevice.getAddress());
            DeviceListSpec deviceSpec = this.mConnectedDevices.get(key);
            boolean isConnected = deviceSpec != null;
            if (isConnected && state != 2) {
                if (btDevice.isBluetoothDock()) {
                    if (state == 0) {
                        makeA2dpDeviceUnavailableLater(address, 8000);
                    }
                } else {
                    makeA2dpDeviceUnavailableNow(address);
                }
            } else if (!isConnected && state == 2) {
                if (btDevice.isBluetoothDock()) {
                    cancelA2dpDeviceTimeout();
                    this.mDockAddress = address;
                } else if (hasScheduledA2dpDockTimeout() && this.mDockAddress != null) {
                    cancelA2dpDeviceTimeout();
                    makeA2dpDeviceUnavailableNow(this.mDockAddress);
                }
                if (a2dpVolume != -1) {
                    VolumeStreamState streamState = this.mStreamStates[3];
                    streamState.setIndex(a2dpVolume * 10, 128, "onSetA2dpSinkConnectionState");
                    setDeviceVolume(streamState, 128);
                }
                makeA2dpDeviceAvailable(address, btDevice.getName(), "onSetA2dpSinkConnectionState");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onSetA2dpSourceConnectionState(BluetoothDevice btDevice, int state) {
        if (DEBUG_VOL) {
            Log.d(TAG, "onSetA2dpSourceConnectionState btDevice=" + btDevice + " state=" + state);
        }
        if (btDevice == null) {
            return;
        }
        String address = btDevice.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
        synchronized (this.mConnectedDevices) {
            String key = makeDeviceListKey(-2147352576, address);
            DeviceListSpec deviceSpec = this.mConnectedDevices.get(key);
            boolean isConnected = deviceSpec != null;
            if (isConnected && state != 2) {
                makeA2dpSrcUnavailable(address);
            } else if (!isConnected && state == 2) {
                makeA2dpSrcAvailable(address);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onSetHearingAidConnectionState(BluetoothDevice btDevice, int state) {
        if (DEBUG_DEVICES) {
            Log.d(TAG, "onSetHearingAidConnectionState btDevice=" + btDevice + ", state=" + state);
        }
        if (btDevice == null) {
            return;
        }
        String address = btDevice.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
        synchronized (this.mConnectedDevices) {
            String key = makeDeviceListKey(134217728, btDevice.getAddress());
            DeviceListSpec deviceSpec = this.mConnectedDevices.get(key);
            boolean isConnected = deviceSpec != null;
            if (isConnected && state != 2) {
                makeHearingAidDeviceUnavailable(address);
            } else if (!isConnected && state == 2) {
                makeHearingAidDeviceAvailable(address, btDevice.getName(), "onSetHearingAidConnectionState");
            }
        }
    }

    private void setCurrentAudioRouteNameIfPossible(String name) {
        synchronized (this.mCurAudioRoutes) {
            if (!TextUtils.equals(this.mCurAudioRoutes.bluetoothName, name) && (name != null || !isCurrentDeviceConnected())) {
                this.mCurAudioRoutes.bluetoothName = name;
                sendMsg(this.mAudioHandler, 12, 1, 0, 0, null, 0);
            }
        }
    }

    private boolean isCurrentDeviceConnected() {
        for (int i = 0; i < this.mConnectedDevices.size(); i++) {
            DeviceListSpec deviceSpec = this.mConnectedDevices.valueAt(i);
            if (TextUtils.equals(deviceSpec.mDeviceName, this.mCurAudioRoutes.bluetoothName)) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onBluetoothA2dpDeviceConfigChange(BluetoothDevice btDevice) {
        if (DEBUG_DEVICES) {
            Log.d(TAG, "onBluetoothA2dpDeviceConfigChange btDevice=" + btDevice);
        }
        if (btDevice == null) {
            return;
        }
        String address = btDevice.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
        AudioEventLogger audioEventLogger = this.mDeviceLogger;
        audioEventLogger.log(new AudioEventLogger.StringEvent("onBluetoothA2dpDeviceConfigChange addr=" + address));
        synchronized (this.mConnectedDevices) {
            if (this.mAudioHandler.hasMessages(102, btDevice)) {
                this.mDeviceLogger.log(new AudioEventLogger.StringEvent("A2dp config change ignored"));
                return;
            }
            String key = makeDeviceListKey(128, address);
            DeviceListSpec deviceSpec = this.mConnectedDevices.get(key);
            if (deviceSpec != null) {
                int musicDevice = getDeviceForStream(3);
                if (AudioSystem.handleDeviceConfigChange(128, address, btDevice.getName()) != 0) {
                    setBluetoothA2dpDeviceConnectionStateInt(btDevice, 0, 2, false, musicDevice, -1);
                }
            }
        }
    }

    public void avrcpSupportsAbsoluteVolume(String address, boolean support) {
        synchronized (this.mA2dpAvrcpLock) {
            this.mAvrcpAbsVolSupported = support;
            sendMsg(this.mAudioHandler, 0, 2, 128, 0, this.mStreamStates[3], 0);
        }
    }

    private boolean handleDeviceConnection(boolean connect, int device, String address, String deviceName) {
        if (DEBUG_DEVICES) {
            Slog.i(TAG, "handleDeviceConnection(" + connect + " dev:" + Integer.toHexString(device) + " address:" + address + " name:" + deviceName + ")");
        }
        synchronized (this.mConnectedDevices) {
            String deviceKey = makeDeviceListKey(device, address);
            if (DEBUG_DEVICES) {
                Slog.i(TAG, "deviceKey:" + deviceKey);
            }
            DeviceListSpec deviceSpec = this.mConnectedDevices.get(deviceKey);
            boolean isConnected = deviceSpec != null;
            if (DEBUG_DEVICES) {
                Slog.i(TAG, "deviceSpec:" + deviceSpec + " is(already)Connected:" + isConnected);
            }
            if (connect && !isConnected) {
                int res = AudioSystem.setDeviceConnectionState(device, 1, address, deviceName);
                if (res == 0) {
                    this.mConnectedDevices.put(deviceKey, new DeviceListSpec(device, deviceName, address));
                    sendMsg(this.mAudioHandler, MSG_ACCESSORY_PLUG_MEDIA_UNMUTE, 2, device, 0, null, 0);
                    return true;
                }
                Slog.e(TAG, "not connecting device 0x" + Integer.toHexString(device) + " due to command error " + res);
                return false;
            } else if (!connect && isConnected) {
                AudioSystem.setDeviceConnectionState(device, 0, address, deviceName);
                this.mConnectedDevices.remove(deviceKey);
                return true;
            } else {
                Log.w(TAG, "handleDeviceConnection() failed, deviceKey=" + deviceKey + ", deviceSpec=" + deviceSpec + ", connect=" + connect);
                return false;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int checkSendBecomingNoisyIntent(int device, int state, int musicDevice) {
        if (state != 0 || (this.mBecomingNoisyIntentDevices & device) == 0) {
            return 0;
        }
        int devices = 0;
        for (int i = 0; i < this.mConnectedDevices.size(); i++) {
            int dev = this.mConnectedDevices.valueAt(i).mDeviceType;
            if ((Integer.MIN_VALUE & dev) == 0 && (this.mBecomingNoisyIntentDevices & dev) != 0) {
                devices |= dev;
            }
        }
        if (musicDevice == 0) {
            musicDevice = getDeviceForStream(3);
        }
        if ((device != musicDevice && !isInCommunication()) || device != devices || hasMediaDynamicPolicy()) {
            return 0;
        }
        this.mAudioHandler.removeMessages(15);
        sendMsg(this.mAudioHandler, 15, 0, 0, 0, null, 0);
        return 1000;
    }

    private boolean hasMediaDynamicPolicy() {
        synchronized (this.mAudioPolicies) {
            if (this.mAudioPolicies.isEmpty()) {
                return false;
            }
            Collection<AudioPolicyProxy> appColl = this.mAudioPolicies.values();
            for (AudioPolicyProxy app : appColl) {
                if (app.hasMixAffectingUsage(1)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void updateAudioRoutes(int device, int state) {
        int newConn;
        int connType = 0;
        if (device == 4) {
            connType = 1;
        } else if (device == 8 || device == 131072) {
            connType = 2;
        } else if (device == 1024 || device == 262144) {
            connType = 8;
        } else if (device == 16384 || device == 67108864) {
            connType = 16;
        }
        synchronized (this.mCurAudioRoutes) {
            if (connType != 0) {
                try {
                    int newConn2 = this.mCurAudioRoutes.mainType;
                    if (state != 0) {
                        newConn = newConn2 | connType;
                    } else {
                        newConn = newConn2 & (~connType);
                    }
                    if (newConn != this.mCurAudioRoutes.mainType) {
                        this.mCurAudioRoutes.mainType = newConn;
                        sendMsg(this.mAudioHandler, 12, 1, 0, 0, null, 0);
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
        }
    }

    private void sendDeviceConnectionIntent(int device, int state, String address, String deviceName) {
        if (DEBUG_DEVICES) {
            Slog.i(TAG, "sendDeviceConnectionIntent(dev:0x" + Integer.toHexString(device) + " state:0x" + Integer.toHexString(state) + " address:" + address + " name:" + deviceName + ");");
        }
        Intent intent = new Intent();
        if (device == 4) {
            intent.setAction("android.intent.action.HEADSET_PLUG");
            intent.putExtra("microphone", 1);
        } else if (device == 8 || device == 131072) {
            intent.setAction("android.intent.action.HEADSET_PLUG");
            intent.putExtra("microphone", 0);
        } else if (device == 67108864) {
            intent.setAction("android.intent.action.HEADSET_PLUG");
            intent.putExtra("microphone", AudioSystem.getDeviceConnectionState(-2113929216, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS) != 1 ? 0 : 1);
        } else if (device == -2113929216) {
            if (AudioSystem.getDeviceConnectionState(67108864, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS) == 1) {
                intent.setAction("android.intent.action.HEADSET_PLUG");
                intent.putExtra("microphone", 1);
            } else {
                return;
            }
        } else if (device == 1024 || device == 262144) {
            configureHdmiPlugIntent(intent, state);
        }
        if (intent.getAction() == null) {
            return;
        }
        intent.putExtra(CONNECT_INTENT_KEY_STATE, state);
        intent.putExtra(CONNECT_INTENT_KEY_ADDRESS, address);
        intent.putExtra(CONNECT_INTENT_KEY_PORT_NAME, deviceName);
        intent.addFlags(1073741824);
        long ident = Binder.clearCallingIdentity();
        try {
            ActivityManager.broadcastStickyIntent(intent, -1);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onSetWiredDeviceConnectionState(int device, int state, String address, String deviceName, String caller) {
        String str;
        boolean z;
        if (DEBUG_DEVICES) {
            StringBuilder sb = new StringBuilder();
            sb.append("onSetWiredDeviceConnectionState(dev:");
            sb.append(Integer.toHexString(device));
            sb.append(" state:");
            sb.append(Integer.toHexString(state));
            sb.append(" address:");
            sb.append(address);
            sb.append(" deviceName:");
            sb.append(deviceName);
            sb.append(" caller: ");
            str = caller;
            sb.append(str);
            sb.append(");");
            Slog.i(TAG, sb.toString());
        } else {
            str = caller;
        }
        synchronized (this.mConnectedDevices) {
            if (state == 0 && (device & DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG) != 0) {
                try {
                    setBluetoothA2dpOnInt(true, "onSetWiredDeviceConnectionState state 0");
                } catch (Throwable th) {
                    throw th;
                }
            }
            if (handleDeviceConnection(state == 1, device, address, deviceName)) {
                if (state != 0) {
                    if ((DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG & device) != 0) {
                        setBluetoothA2dpOnInt(false, "onSetWiredDeviceConnectionState state not 0");
                    }
                    if ((67108876 & device) != 0) {
                        z = false;
                        sendMsg(this.mAudioHandler, 14, 0, 0, 0, str, MUSIC_ACTIVE_POLL_PERIOD_MS);
                    } else {
                        z = false;
                    }
                    if (isPlatformTelevision() && (device & 1024) != 0) {
                        this.mFixedVolumeDevices |= 1024;
                        checkAllFixedVolumeDevices();
                        if (this.mHdmiManager != null) {
                            synchronized (this.mHdmiManager) {
                                if (this.mHdmiPlaybackClient != null) {
                                    this.mHdmiCecSink = z;
                                    this.mHdmiPlaybackClient.queryDisplayStatus(this.mHdmiDisplayStatusCallback);
                                }
                            }
                        }
                    }
                    if ((device & 1024) != 0) {
                        sendEnabledSurroundFormats(this.mContentResolver, true);
                    }
                } else if (isPlatformTelevision() && (device & 1024) != 0 && this.mHdmiManager != null) {
                    synchronized (this.mHdmiManager) {
                        this.mHdmiCecSink = false;
                    }
                }
                sendDeviceConnectionIntent(device, state, address, deviceName);
                updateAudioRoutes(device, state);
            }
        }
    }

    private void configureHdmiPlugIntent(Intent intent, int state) {
        int[] channelMasks;
        intent.setAction("android.media.action.HDMI_AUDIO_PLUG");
        intent.putExtra("android.media.extra.AUDIO_PLUG_STATE", state);
        if (state == 1) {
            ArrayList<AudioPort> ports = new ArrayList<>();
            int[] portGeneration = new int[1];
            int status = AudioSystem.listAudioPorts(ports, portGeneration);
            if (status == 0) {
                Iterator<AudioPort> it = ports.iterator();
                while (it.hasNext()) {
                    AudioPort next = it.next();
                    if (next instanceof AudioDevicePort) {
                        AudioDevicePort devicePort = (AudioDevicePort) next;
                        if (devicePort.type() == 1024 || devicePort.type() == 262144) {
                            int[] formats = AudioFormat.filterPublicFormats(devicePort.formats());
                            if (formats.length > 0) {
                                ArrayList<Integer> encodingList = new ArrayList<>(1);
                                for (int format : formats) {
                                    if (format != 0) {
                                        encodingList.add(Integer.valueOf(format));
                                    }
                                }
                                int[] encodingArray = new int[encodingList.size()];
                                for (int i = 0; i < encodingArray.length; i++) {
                                    encodingArray[i] = encodingList.get(i).intValue();
                                }
                                intent.putExtra("android.media.extra.ENCODINGS", encodingArray);
                            }
                            int maxChannels = 0;
                            for (int mask : devicePort.channelMasks()) {
                                int channelCount = AudioFormat.channelCountFromOutChannelMask(mask);
                                if (channelCount > maxChannels) {
                                    maxChannels = channelCount;
                                }
                            }
                            intent.putExtra("android.media.extra.MAX_CHANNEL_COUNT", maxChannels);
                        }
                    }
                }
            }
        }
    }

    /* loaded from: classes.dex */
    private class AudioServiceBroadcastReceiver extends BroadcastReceiver {
        private AudioServiceBroadcastReceiver() {
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            int config = 0;
            if (action.equals("android.intent.action.DOCK_EVENT")) {
                int dockState = intent.getIntExtra("android.intent.extra.DOCK_STATE", 0);
                switch (dockState) {
                    case 1:
                        config = 7;
                        break;
                    case 2:
                        config = 6;
                        break;
                    case 3:
                        config = 8;
                        break;
                    case 4:
                        config = 9;
                        break;
                }
                if (dockState != 3 && (dockState != 0 || AudioService.this.mDockState != 3)) {
                    AudioService.this.mForceUseLogger.log(new AudioServiceEvents.ForceUseEvent(3, config, "ACTION_DOCK_EVENT intent"));
                    AudioSystem.setForceUse(3, config);
                }
                AudioService.this.mDockState = dockState;
            } else if (action.equals("android.bluetooth.headset.profile.action.ACTIVE_DEVICE_CHANGED")) {
                BluetoothDevice btDevice = (BluetoothDevice) intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE");
                AudioService.this.setBtScoActiveDevice(btDevice);
            } else {
                boolean z = true;
                if (action.equals("android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED")) {
                    boolean broadcast = false;
                    int scoAudioState = -1;
                    synchronized (AudioService.this.mScoClients) {
                        int btState = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1);
                        if (!AudioService.this.mScoClients.isEmpty() && (AudioService.this.mScoAudioState == 3 || AudioService.this.mScoAudioState == 1 || AudioService.this.mScoAudioState == 4 || AudioService.this.mScoAudioState == 5)) {
                            broadcast = true;
                        }
                        switch (btState) {
                            case 10:
                                AudioService.this.setBluetoothScoOn(false);
                                scoAudioState = 0;
                                if (AudioService.this.mScoAudioState == 1 && AudioService.this.mBluetoothHeadset != null && AudioService.this.mBluetoothHeadsetDevice != null && AudioService.connectBluetoothScoAudioHelper(AudioService.this.mBluetoothHeadset, AudioService.this.mBluetoothHeadsetDevice, AudioService.this.mScoAudioMode)) {
                                    AudioService.this.mScoAudioState = 3;
                                    broadcast = false;
                                    break;
                                } else {
                                    AudioService audioService = AudioService.this;
                                    if (AudioService.this.mScoAudioState != 3) {
                                        z = false;
                                    }
                                    audioService.clearAllScoClients(0, z);
                                    AudioService.this.mScoAudioState = 0;
                                    break;
                                }
                            case 11:
                                if (AudioService.this.mScoAudioState != 3 && AudioService.this.mScoAudioState != 4) {
                                    AudioService.this.mScoAudioState = 2;
                                }
                                broadcast = false;
                                break;
                            case 12:
                                scoAudioState = 1;
                                if (AudioService.this.mScoAudioState != 3 && AudioService.this.mScoAudioState != 4) {
                                    AudioService.this.mScoAudioState = 2;
                                }
                                AudioService.this.setBluetoothScoOn(true);
                                break;
                            default:
                                broadcast = false;
                                break;
                        }
                    }
                    if (broadcast) {
                        AudioService.this.broadcastScoConnectionState(scoAudioState);
                        Intent newIntent = new Intent("android.media.SCO_AUDIO_STATE_CHANGED");
                        newIntent.putExtra("android.media.extra.SCO_AUDIO_STATE", scoAudioState);
                        AudioService.this.sendStickyBroadcastToAll(newIntent);
                    }
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
                        AudioService.sendMsg(AudioService.this.mAudioHandler, 15, 0, 0, 0, null, 0);
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
                        AudioService.this.disconnectAllBluetoothProfiles();
                    }
                } else if (action.equals("android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION") || action.equals("android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION")) {
                    AudioService.this.handleAudioEffectBroadcast(context, intent);
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
            boolean isRestricted2 = true;
            boolean wasRestricted2 = prevRestrictions.getBoolean("no_adjust_volume") || prevRestrictions.getBoolean("disallow_unmute_device");
            if (!newRestrictions.getBoolean("no_adjust_volume") && !newRestrictions.getBoolean("disallow_unmute_device")) {
                isRestricted2 = false;
            }
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
        if (ril != null && ril.size() != 0 && (ri = ril.get(0)) != null && ri.activityInfo != null && ri.activityInfo.packageName != null) {
            intent.setPackage(ri.activityInfo.packageName);
            context.sendBroadcastAsUser(intent, UserHandle.ALL);
            return;
        }
        Log.w(TAG, "couldn't find receiver package for effect intent");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void killBackgroundUserProcessesWithRecordAudioPermission(UserInfo oldUser) {
        PackageManager pm = this.mContext.getPackageManager();
        ComponentName homeActivityName = null;
        if (!oldUser.isManagedProfile()) {
            homeActivityName = this.mActivityManagerInternal.getHomeActivityForUser(oldUser.id);
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
        String str;
        if ((flags & 4) == 4) {
            str = clientId;
            if ("AudioFocus_For_Phone_Ring_And_Calls".equals(str)) {
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
        } else {
            str = clientId;
        }
        return this.mMediaFocusControl.requestAudioFocus(aa, durationHint, cb, fd, str, callingPackageName, flags, sdk, forceFocusDuckingForAccessibility(aa, durationHint, Binder.getCallingUid()));
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

    public String getCurrentAudioFocusPackageName() {
        return this.mMediaFocusControl.getCurrentAudioFocusPackageName();
    }

    public List<String> getAudioFocusPackageNameList() {
        return this.mMediaFocusControl.getAudioFocusPackageNameList();
    }

    public List<xpAudioSessionInfo> getActiveSessionList() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.getActiveSessionList();
            }
            return null;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.getActiveSessionList();
        } else {
            return null;
        }
    }

    public String getLastAudioFocusPackageName() {
        return this.mMediaFocusControl.getLastAudioFocusPackageName();
    }

    public void startAudioCapture(int audioSession, int usage) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.startAudioCapture(audioSession, usage);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.startAudioCapture(audioSession, usage);
        }
    }

    public void stopAudioCapture(int audioSession, int usage) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.stopAudioCapture(audioSession, usage);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.stopAudioCapture(audioSession, usage);
        }
    }

    public void startSpeechEffect(int audioSession) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.startSpeechEffect(audioSession);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.startSpeechEffect(audioSession);
        }
    }

    public void stopSpeechEffect(int audioSession) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.stopSpeechEffect(audioSession);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.stopSpeechEffect(audioSession);
        }
    }

    public boolean checkStreamActive(int streamType) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.checkStreamActive(streamType);
            }
            return false;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.checkStreamActive(streamType);
        } else {
            return false;
        }
    }

    public boolean isAnyStreamActive() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.isAnyStreamActive();
            }
            return false;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.isAnyStreamActive();
        } else {
            return false;
        }
    }

    public int checkStreamCanPlay(int streamType) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.checkStreamCanPlay(streamType);
            }
            return 2;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.checkStreamCanPlay(streamType);
        } else {
            return 2;
        }
    }

    public void setMusicLimitMode(boolean mode) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setMusicLimitMode(mode);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.setMusicLimitMode(mode);
        }
    }

    public boolean isMusicLimitMode() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.isMusicLimitMode();
            }
            return false;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.isMusicLimitMode();
        } else {
            return false;
        }
    }

    private boolean readCameraSoundForced() {
        return SystemProperties.getBoolean("audio.camerasound.force", false) || this.mContext.getResources().getBoolean(17956910);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleConfigurationChanged(Context context) {
        try {
            Configuration config = context.getResources().getConfiguration();
            sendMsg(this.mAudioHandler, 16, 0, 0, 0, TAG, 0);
            boolean cameraSoundForced = readCameraSoundForced();
            synchronized (this.mSettingsLock) {
                int i = 0;
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
                    AudioHandler audioHandler = this.mAudioHandler;
                    if (cameraSoundForced) {
                        i = 11;
                    }
                    sendMsg(audioHandler, 8, 2, 4, i, new String("handleConfigurationChanged"), 0);
                    sendMsg(this.mAudioHandler, 10, 2, 0, 0, this.mStreamStates[7], 0);
                }
            }
            this.mVolumeController.setLayoutDirection(config.getLayoutDirection());
        } catch (Exception e) {
            Log.e(TAG, "Error handling configuration change: ", e);
        }
    }

    public void setBluetoothA2dpOnInt(boolean on, String eventSource) {
        synchronized (this.mBluetoothA2dpEnabledLock) {
            this.mBluetoothA2dpEnabled = on;
            this.mAudioHandler.removeMessages(13);
            setForceUseInt_SyncDevices(1, this.mBluetoothA2dpEnabled ? 0 : 10, eventSource);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setForceUseInt_SyncDevices(int usage, int config, String eventSource) {
        if (usage == 1) {
            sendMsg(this.mAudioHandler, 12, 1, 0, 0, null, 0);
        }
        this.mForceUseLogger.log(new AudioServiceEvents.ForceUseEvent(usage, config, eventSource));
        AudioSystem.setForceUse(usage, config);
    }

    public void setRingtonePlayer(IRingtonePlayer player) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.REMOTE_AUDIO_PLAYBACK", null);
        this.mRingtonePlayer = player;
    }

    public IRingtonePlayer getRingtonePlayer() {
        return this.mRingtonePlayer;
    }

    public AudioRoutesInfo startWatchingRoutes(IAudioRoutesObserver observer) {
        AudioRoutesInfo routes;
        synchronized (this.mCurAudioRoutes) {
            routes = new AudioRoutesInfo(this.mCurAudioRoutes);
            this.mRoutesObservers.register(observer);
        }
        return routes;
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
        synchronized (this.mSafeMediaVolumeState) {
            if (this.mSafeMediaVolumeState.intValue() != 0 && this.mSafeMediaVolumeState.intValue() != 1) {
                if (on && this.mSafeMediaVolumeState.intValue() == 2) {
                    this.mSafeMediaVolumeState = 3;
                    enforceSafeMediaVolume(caller);
                } else if (!on && this.mSafeMediaVolumeState.intValue() == 3) {
                    this.mSafeMediaVolumeState = 2;
                    this.mMusicActiveMs = 1;
                    saveMusicActiveMs();
                    sendMsg(this.mAudioHandler, 14, 0, 0, 0, caller, MUSIC_ACTIVE_POLL_PERIOD_MS);
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
            int i3 = device & devices;
            if (i3 != 0) {
                int index = streamState.getIndex(device);
                if (index > safeMediaVolumeIndex(device)) {
                    streamState.setIndex(safeMediaVolumeIndex(device), device, caller);
                    sendMsg(this.mAudioHandler, 0, 2, device, 0, streamState, 0);
                }
                devices &= ~device;
            }
            i = i2;
        }
    }

    private boolean checkSafeMediaVolume(int streamType, int index, int device) {
        synchronized (this.mSafeMediaVolumeState) {
            if (this.mSafeMediaVolumeState.intValue() == 3 && mStreamVolumeAlias[streamType] == 3 && (67108876 & device) != 0 && index > safeMediaVolumeIndex(device)) {
                return false;
            }
            return true;
        }
    }

    public void disableSafeMediaVolume(String callingPackage) {
        enforceVolumeController("disable the safe media volume");
        synchronized (this.mSafeMediaVolumeState) {
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
            if (AudioService.this.mHdmiManager != null) {
                synchronized (AudioService.this.mHdmiManager) {
                    AudioService.this.mHdmiCecSink = status != -1;
                    if (AudioService.this.isPlatformTelevision() && !AudioService.this.mHdmiCecSink) {
                        AudioService.this.mFixedVolumeDevices &= -1025;
                    }
                    AudioService.this.checkAllFixedVolumeDevices();
                }
            }
        }
    }

    public int setHdmiSystemAudioSupported(boolean on) {
        int device = 0;
        if (this.mHdmiManager != null) {
            synchronized (this.mHdmiManager) {
                if (this.mHdmiTvClient == null) {
                    Log.w(TAG, "Only Hdmi-Cec enabled TV device supports system audio mode.");
                    return 0;
                }
                synchronized (this.mHdmiTvClient) {
                    if (this.mHdmiSystemAudioSupported != on) {
                        this.mHdmiSystemAudioSupported = on;
                        int config = on ? 12 : 0;
                        this.mForceUseLogger.log(new AudioServiceEvents.ForceUseEvent(5, config, "setHdmiSystemAudioSupported"));
                        AudioSystem.setForceUse(5, config);
                    }
                    device = getDevicesForStream(3);
                }
            }
        }
        return device;
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
        if (DEBUG_VOL) {
            Log.d(TAG, "Touch exploration enabled=" + touchExploreEnabled + " stream override delay is now " + sStreamOverrideDelayMs + " ms");
        }
    }

    public void onAccessibilityServicesStateChanged(AccessibilityManager accessibilityManager) {
        updateA11yVolumeAlias(accessibilityManager.isAccessibilityVolumeStreamActive());
    }

    private void updateA11yVolumeAlias(boolean a11VolEnabled) {
        if (DEBUG_VOL) {
            Log.d(TAG, "Accessibility volume enabled = " + a11VolEnabled);
        }
        if (sIndependentA11yVolume != a11VolEnabled) {
            sIndependentA11yVolume = a11VolEnabled;
            updateStreamVolumeAlias(true, TAG);
            this.mVolumeController.setA11yMode(sIndependentA11yVolume ? 1 : 0);
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
            if (args != null && args.length == 3) {
                try {
                    int msg = Integer.parseInt(args[0]);
                    int param1 = Integer.parseInt(args[1]);
                    int param2 = Integer.parseInt(args[2]);
                    this.mAudioHandler.sendMessage(this.mAudioHandler.obtainMessage(msg, param1, param2));
                    return;
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Incorrect param");
                    return;
                }
            }
            this.mMediaFocusControl.dump(pw);
            dumpStreamStates(pw);
            dumpRingerMode(pw);
            pw.println("\nAudio routes:");
            pw.print("  mMainType=0x");
            pw.println(Integer.toHexString(this.mCurAudioRoutes.mainType));
            pw.print("  mBluetoothName=");
            pw.println(this.mCurAudioRoutes.bluetoothName);
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
            pw.println(this.mAvrcpAbsVolSupported);
            dumpAudioPolicies(pw);
            this.mDynPolicyLogger.dump(pw);
            this.mPlaybackMonitor.dump(pw);
            this.mRecordMonitor.dump(pw);
            pw.println("\n");
            pw.println("\nEvent logs:");
            this.mModeLogger.dump(pw);
            pw.println("\n");
            this.mDeviceLogger.dump(pw);
            pw.println("\n");
            this.mForceUseLogger.dump(pw);
            pw.println("\n");
            this.mVolumeLogger.dump(pw);
        }
    }

    private static String safeMediaVolumeStateToString(Integer state) {
        switch (state.intValue()) {
            case 0:
                return "SAFE_MEDIA_VOLUME_NOT_CONFIGURED";
            case 1:
                return "SAFE_MEDIA_VOLUME_DISABLED";
            case 2:
                return "SAFE_MEDIA_VOLUME_INACTIVE";
            case 3:
                return "SAFE_MEDIA_VOLUME_ACTIVE";
            default:
                return null;
        }
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
        if (DEBUG_VOL) {
            Log.d(TAG, "Volume controller: " + this.mVolumeController);
        }
    }

    public void notifyVolumeControllerVisible(IVolumeController controller, boolean visible) {
        enforceVolumeController("notify about volume controller visibility");
        if (!this.mVolumeController.isSameBinder(controller)) {
            return;
        }
        this.mVolumeController.setVisible(visible);
        if (DEBUG_VOL) {
            Log.d(TAG, "Volume controller visible: " + visible);
        }
    }

    public void setVolumePolicy(VolumePolicy policy) {
        enforceVolumeController("set volume policy");
        if (policy != null && !policy.equals(this.mVolumePolicy)) {
            this.mVolumePolicy = policy;
            if (DEBUG_VOL) {
                Log.d(TAG, "Volume policy changed: " + this.mVolumePolicy);
            }
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
            } else if (this.mNextLongPress <= 0) {
                return false;
            } else {
                if (now > this.mNextLongPress) {
                    this.mNextLongPress = 0L;
                    return false;
                }
                return true;
            }
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
            if (this.mController == null) {
                return;
            }
            try {
                this.mController.displaySafeVolumeWarning(flags);
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling displaySafeVolumeWarning", e);
            }
        }

        public void postVolumeChanged(int streamType, int flags) {
            if (this.mController == null) {
                return;
            }
            try {
                this.mController.volumeChanged(streamType, flags);
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling volumeChanged", e);
            }
        }

        public void postMasterMuteChanged(int flags) {
            if (this.mController == null) {
                return;
            }
            try {
                this.mController.masterMuteChanged(flags);
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling masterMuteChanged", e);
            }
        }

        public void setLayoutDirection(int layoutDirection) {
            if (this.mController == null) {
                return;
            }
            try {
                this.mController.setLayoutDirection(layoutDirection);
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling setLayoutDirection", e);
            }
        }

        public void postDismiss() {
            if (this.mController == null) {
                return;
            }
            try {
                this.mController.dismiss();
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling dismiss", e);
            }
        }

        public void setA11yMode(int a11yMode) {
            if (this.mController == null) {
                return;
            }
            try {
                this.mController.setA11yMode(a11yMode);
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
                setRingerModeInternal(getRingerModeInternal(), "AudioService.setRingerModeDelegate");
            }
        }

        public void adjustSuggestedStreamVolumeForUid(int streamType, int direction, int flags, String callingPackage, int uid) {
            AudioService.this.adjustSuggestedStreamVolume(direction, streamType, flags, callingPackage, callingPackage, uid);
        }

        public void adjustStreamVolumeForUid(int streamType, int direction, int flags, String callingPackage, int uid) {
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

        /* JADX WARN: Removed duplicated region for block: B:15:0x0031 A[Catch: all -> 0x005a, LOOP:0: B:15:0x0031->B:20:0x004a, LOOP_START, PHI: r2 
          PHI: (r2v2 'i' int) = (r2v0 'i' int), (r2v3 'i' int) binds: [B:14:0x002e, B:20:0x004a] A[DONT_GENERATE, DONT_INLINE], TryCatch #0 {, blocks: (B:4:0x0007, B:6:0x000d, B:23:0x0058, B:7:0x0014, B:9:0x001d, B:15:0x0031, B:17:0x003a, B:20:0x004a, B:22:0x004f), top: B:28:0x0007 }] */
        /* JADX WARN: Removed duplicated region for block: B:22:0x004f A[Catch: all -> 0x005a, TryCatch #0 {, blocks: (B:4:0x0007, B:6:0x000d, B:23:0x0058, B:7:0x0014, B:9:0x001d, B:15:0x0031, B:17:0x003a, B:20:0x004a, B:22:0x004f), top: B:28:0x0007 }] */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public void setAccessibilityServiceUids(android.util.IntArray r6) {
            /*
                r5 = this;
                com.android.server.audio.AudioService r0 = com.android.server.audio.AudioService.this
                java.lang.Object r0 = com.android.server.audio.AudioService.access$12000(r0)
                monitor-enter(r0)
                int r1 = r6.size()     // Catch: java.lang.Throwable -> L5a
                if (r1 != 0) goto L14
                com.android.server.audio.AudioService r1 = com.android.server.audio.AudioService.this     // Catch: java.lang.Throwable -> L5a
                r2 = 0
                com.android.server.audio.AudioService.access$12102(r1, r2)     // Catch: java.lang.Throwable -> L5a
                goto L58
            L14:
                com.android.server.audio.AudioService r1 = com.android.server.audio.AudioService.this     // Catch: java.lang.Throwable -> L5a
                int[] r1 = com.android.server.audio.AudioService.access$12100(r1)     // Catch: java.lang.Throwable -> L5a
                r2 = 0
                if (r1 == 0) goto L2d
                com.android.server.audio.AudioService r1 = com.android.server.audio.AudioService.this     // Catch: java.lang.Throwable -> L5a
                int[] r1 = com.android.server.audio.AudioService.access$12100(r1)     // Catch: java.lang.Throwable -> L5a
                int r1 = r1.length     // Catch: java.lang.Throwable -> L5a
                int r3 = r6.size()     // Catch: java.lang.Throwable -> L5a
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
                com.android.server.audio.AudioService r3 = com.android.server.audio.AudioService.this     // Catch: java.lang.Throwable -> L5a
                int[] r3 = com.android.server.audio.AudioService.access$12100(r3)     // Catch: java.lang.Throwable -> L5a
                int r3 = r3.length     // Catch: java.lang.Throwable -> L5a
                if (r2 >= r3) goto L4d
                int r3 = r6.get(r2)     // Catch: java.lang.Throwable -> L5a
                com.android.server.audio.AudioService r4 = com.android.server.audio.AudioService.this     // Catch: java.lang.Throwable -> L5a
                int[] r4 = com.android.server.audio.AudioService.access$12100(r4)     // Catch: java.lang.Throwable -> L5a
                r4 = r4[r2]     // Catch: java.lang.Throwable -> L5a
                if (r3 == r4) goto L4a
                r1 = 1
                goto L4d
            L4a:
                int r2 = r2 + 1
                goto L31
            L4d:
                if (r1 == 0) goto L58
                com.android.server.audio.AudioService r2 = com.android.server.audio.AudioService.this     // Catch: java.lang.Throwable -> L5a
                int[] r3 = r6.toArray()     // Catch: java.lang.Throwable -> L5a
                com.android.server.audio.AudioService.access$12102(r2, r3)     // Catch: java.lang.Throwable -> L5a
            L58:
                monitor-exit(r0)     // Catch: java.lang.Throwable -> L5a
                return
            L5a:
                r1 = move-exception
                monitor-exit(r0)     // Catch: java.lang.Throwable -> L5a
                throw r1
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.audio.AudioService.AudioServiceInternal.setAccessibilityServiceUids(android.util.IntArray):void");
        }
    }

    public String registerAudioPolicy(AudioPolicyConfig policyConfig, IAudioPolicyCallback pcb, boolean hasFocusListener, boolean isFocusPolicy, boolean isVolumeController) {
        AudioSystem.setDynamicPolicyCallback(this.mDynPolicyCallback);
        boolean hasPermissionForPolicy = this.mContext.checkCallingPermission("android.permission.MODIFY_AUDIO_ROUTING") == 0;
        if (!hasPermissionForPolicy) {
            Slog.w(TAG, "Can't register audio policy for pid " + Binder.getCallingPid() + " / uid " + Binder.getCallingUid() + ", need MODIFY_AUDIO_ROUTING");
            return null;
        }
        AudioEventLogger audioEventLogger = this.mDynPolicyLogger;
        audioEventLogger.log(new AudioEventLogger.StringEvent("registerAudioPolicy for " + pcb.asBinder() + " with config:" + policyConfig).printLog(TAG));
        synchronized (this.mAudioPolicies) {
            try {
                try {
                    try {
                        if (this.mAudioPolicies.containsKey(pcb.asBinder())) {
                            Slog.e(TAG, "Cannot re-register policy");
                            return null;
                        }
                        AudioPolicyProxy app = new AudioPolicyProxy(policyConfig, pcb, hasFocusListener, isFocusPolicy, isVolumeController);
                        pcb.asBinder().linkToDeath(app, 0);
                        String regId = app.getRegistrationId();
                        this.mAudioPolicies.put(pcb.asBinder(), app);
                        return regId;
                    } catch (RemoteException e) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Audio policy registration failed, could not link to ");
                        sb.append(pcb);
                        sb.append(" binder death");
                        Slog.w(TAG, sb.toString(), e);
                        return null;
                    }
                } catch (Throwable th) {
                    e = th;
                    throw e;
                }
            } catch (Throwable th2) {
                e = th2;
            }
        }
    }

    public void unregisterAudioPolicyAsync(IAudioPolicyCallback pcb) {
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

    @GuardedBy("mAudioPolicies")
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
        if (DEBUG_AP) {
            Log.d(TAG, "addMixForPolicy for " + pcb.asBinder() + " with config:" + policyConfig);
        }
        synchronized (this.mAudioPolicies) {
            AudioPolicyProxy app = checkUpdateForPolicy(pcb, "Cannot add AudioMix in audio policy");
            if (app == null) {
                return -1;
            }
            app.addMixes(policyConfig.getMixes());
            return 0;
        }
    }

    public int removeMixForPolicy(AudioPolicyConfig policyConfig, IAudioPolicyCallback pcb) {
        if (DEBUG_AP) {
            Log.d(TAG, "removeMixForPolicy for " + pcb.asBinder() + " with config:" + policyConfig);
        }
        synchronized (this.mAudioPolicies) {
            AudioPolicyProxy app = checkUpdateForPolicy(pcb, "Cannot add AudioMix in audio policy");
            if (app == null) {
                return -1;
            }
            app.removeMixes(policyConfig.getMixes());
            return 0;
        }
    }

    public int setFocusPropertiesForPolicy(int duckingBehavior, IAudioPolicyCallback pcb) {
        if (DEBUG_AP) {
            Log.d(TAG, "setFocusPropertiesForPolicy() duck behavior=" + duckingBehavior + " policy " + pcb.asBinder());
        }
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

    /* JADX INFO: Access modifiers changed from: private */
    public void setExtVolumeController(IAudioPolicyCallback apc) {
        if (!this.mContext.getResources().getBoolean(17956984)) {
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
        if (DEBUG_AP) {
            Log.d(TAG, "onDynamicPolicyMixStateUpdate(" + regId + ", " + state + ")");
        }
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

    /* loaded from: classes.dex */
    public class AudioPolicyProxy extends AudioPolicyConfig implements IBinder.DeathRecipient {
        private static final String TAG = "AudioPolicyProxy";
        int mFocusDuckBehavior;
        final boolean mHasFocusListener;
        boolean mIsFocusPolicy;
        final boolean mIsVolumeController;
        final IAudioPolicyCallback mPolicyCallback;

        AudioPolicyProxy(AudioPolicyConfig config, IAudioPolicyCallback token, boolean hasFocusListener, boolean isFocusPolicy, boolean isVolumeController) {
            super(config);
            this.mFocusDuckBehavior = 0;
            this.mIsFocusPolicy = false;
            setRegistration(new String(config.hashCode() + ":ap:" + AudioService.access$12208(AudioService.this)));
            this.mPolicyCallback = token;
            this.mHasFocusListener = hasFocusListener;
            this.mIsVolumeController = isVolumeController;
            if (this.mHasFocusListener) {
                AudioService.this.mMediaFocusControl.addFocusFollower(this.mPolicyCallback);
                if (isFocusPolicy) {
                    this.mIsFocusPolicy = true;
                    AudioService.this.mMediaFocusControl.setFocusPolicy(this.mPolicyCallback);
                }
            }
            if (this.mIsVolumeController) {
                AudioService.this.setExtVolumeController(this.mPolicyCallback);
            }
            connectMixes();
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
                AudioService.this.mMediaFocusControl.unsetFocusPolicy(this.mPolicyCallback);
            }
            if (this.mFocusDuckBehavior == 1) {
                AudioService.this.mMediaFocusControl.setDuckingInExtPolicyAvailable(false);
            }
            if (this.mHasFocusListener) {
                AudioService.this.mMediaFocusControl.removeFocusFollower(this.mPolicyCallback);
            }
            long identity = Binder.clearCallingIdentity();
            AudioSystem.registerPolicyMixes(this.mMixes, false);
            Binder.restoreCallingIdentity(identity);
        }

        boolean hasMixAffectingUsage(int usage) {
            Iterator it = this.mMixes.iterator();
            while (it.hasNext()) {
                AudioMix mix = (AudioMix) it.next();
                if (mix.isAffectingUsage(usage)) {
                    return true;
                }
            }
            return false;
        }

        void addMixes(ArrayList<AudioMix> mixes) {
            synchronized (this.mMixes) {
                AudioSystem.registerPolicyMixes(this.mMixes, false);
                add(mixes);
                AudioSystem.registerPolicyMixes(this.mMixes, true);
            }
        }

        void removeMixes(ArrayList<AudioMix> mixes) {
            synchronized (this.mMixes) {
                AudioSystem.registerPolicyMixes(this.mMixes, false);
                remove(mixes);
                AudioSystem.registerPolicyMixes(this.mMixes, true);
            }
        }

        void connectMixes() {
            long identity = Binder.clearCallingIdentity();
            AudioSystem.registerPolicyMixes(this.mMixes, true);
            Binder.restoreCallingIdentity(identity);
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
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setSoundField(mode, xSound, ySound);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.setSoundField(mode, xSound, ySound);
        }
    }

    public SoundField getSoundField(int mode) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.getSoundField(mode);
            }
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.getSoundField(mode);
        }
        return new SoundField(0, 0);
    }

    public int getSoundEffectMode() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.getSoundEffectMode();
            }
            return -1;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.getSoundEffectMode();
        } else {
            return -1;
        }
    }

    public void setSoundEffectMode(int mode) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setSoundEffectMode(mode);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.setSoundEffectMode(mode);
        }
    }

    public void setSoundEffectType(int mode, int type) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setSoundEffectType(mode, type);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.setSoundEffectType(mode, type);
        }
    }

    public int getSoundEffectType(int mode) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.getSoundEffectType(mode);
            }
            return -1;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.getSoundEffectType(mode);
        } else {
            return -1;
        }
    }

    public void setNavVolDecreaseEnable(boolean enable) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setNavVolDecreaseEnable(enable);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.setNavVolDecreaseEnable(enable);
        }
    }

    public boolean getNavVolDecreaseEnable() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.getNavVolDecreaseEnable();
            }
            return true;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.getNavVolDecreaseEnable();
        } else {
            return true;
        }
    }

    public void setXpCustomizeEffect(int type, int value) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setXpCustomizeEffect(type, value);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.setXpCustomizeEffect(type, value);
        }
    }

    public int getXpCustomizeEffect(int type) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.getXpCustomizeEffect(type);
            }
            return 0;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.getXpCustomizeEffect(type);
        } else {
            return 0;
        }
    }

    public void flushXpCustomizeEffects(int[] values) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.flushXpCustomizeEffects(values);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.flushXpCustomizeEffects(values);
        }
    }

    public void setSoundEffectScene(int mode, int type) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setSoundEffectScene(mode, type);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.setSoundEffectScene(mode, type);
        }
    }

    public int getSoundEffectScene(int mode) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.getSoundEffectScene(mode);
            }
            return -1;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.getSoundEffectScene(mode);
        } else {
            return -1;
        }
    }

    public void setSoundEffectParms(int effectType, int nativeValue, int softValue, int innervationValue) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setSoundEffectParms(effectType, nativeValue, softValue, innervationValue);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.setSoundEffectParms(effectType, nativeValue, softValue, innervationValue);
        }
    }

    public SoundEffectParms getSoundEffectParms(int effectType, int modeType) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.getSoundEffectParms(effectType, modeType);
            }
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.getSoundEffectParms(effectType, modeType);
        }
        return new SoundEffectParms(0, 0, 0);
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
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.applyUsage(usage, id);
                return -1;
            }
            return -1;
        } else if (this.mXpAudioPloicy != null) {
            return this.mXpAudioPloicy.applyUsage(usage, id, pkgName);
        } else {
            return -1;
        }
    }

    public void releaseUsage(int usage, int id, String pkgName) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.releaseUsage(usage, id);
            }
        } else if (this.mXpAudioPloicy != null) {
            this.mXpAudioPloicy.releaseUsage(usage, id, pkgName);
        }
    }

    public boolean isUsageActive(int usage) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.isUsageActive(usage);
            }
            return false;
        } else if (this.mXpAudioPloicy != null) {
            return this.mXpAudioPloicy.isUsageActive(usage);
        } else {
            return false;
        }
    }

    public void setStereoAlarm(boolean enable) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setStereoAlarm(enable);
            }
        } else if (this.mXpAudioPloicy != null) {
            this.mXpAudioPloicy.setStereoAlarm(enable);
        }
    }

    public void setSpeechSurround(boolean enable) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setSpeechSurround(enable);
            }
        } else if (this.mXpAudioPloicy != null) {
            this.mXpAudioPloicy.setSpeechSurround(enable);
        }
    }

    public void setMainDriver(boolean enable) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setMainDriver(enable);
            }
        } else if (this.mXpAudioPloicy != null) {
            this.mXpAudioPloicy.setMainDriver(enable);
        }
    }

    public void setMainDriverMode(int mode) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setMainDriverMode(mode);
            }
        } else if (this.mXpAudioPloicy != null) {
            this.mXpAudioPloicy.setMainDriverMode(mode);
        }
    }

    public int getMainDriverMode() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.getMainDriverMode();
            }
            return 0;
        } else if (this.mXpAudioPloicy != null) {
            return this.mXpAudioPloicy.getMainDriverMode();
        } else {
            return 0;
        }
    }

    public void setRingtoneSessionId(int streamType, int sessionId, String pkgName) {
        Log.i(TAG, "setRingtoneSessionId");
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setRingtoneSessionId(streamType, sessionId, pkgName);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.setRingtoneSessionId(streamType, sessionId, pkgName);
        }
    }

    public void setBanVolumeChangeMode(int streamType, int mode, String pkgName) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setBanVolumeChangeMode(streamType, mode, pkgName);
            }
        } else if (this.mXpAudioPloicy != null) {
            this.mXpAudioPloicy.setBanVolumeChangeMode(streamType, mode, pkgName);
        }
    }

    public int getBanVolumeChangeMode(int streamType) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.getBanVolumeChangeMode(streamType);
            }
            return 0;
        } else if (this.mXpAudioPloicy != null) {
            return this.mXpAudioPloicy.getBanVolumeChangeMode(streamType);
        } else {
            return 0;
        }
    }

    public void setBtHeadPhone(boolean enable) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setBtHeadPhone(enable);
            }
        } else if (this.mXpAudioPloicy != null) {
            this.mXpAudioPloicy.setBtHeadPhone(enable);
        }
    }

    public boolean isStereoAlarmOn() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.isStereoAlarmOn();
            }
            return false;
        } else if (this.mXpAudioPloicy != null) {
            return this.mXpAudioPloicy.isStereoAlarmOn();
        } else {
            return false;
        }
    }

    public boolean isSpeechSurroundOn() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.isSpeechSurroundOn();
            }
            return false;
        } else if (this.mXpAudioPloicy != null) {
            return this.mXpAudioPloicy.isSpeechSurroundOn();
        } else {
            return false;
        }
    }

    public boolean isMainDriverOn() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.isMainDriverOn();
            }
            return false;
        } else if (this.mXpAudioPloicy != null) {
            return this.mXpAudioPloicy.isMainDriverOn();
        } else {
            return false;
        }
    }

    public boolean isBtHeadPhoneOn() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.isBtHeadPhoneOn();
            }
            return false;
        } else if (this.mXpAudioPloicy != null) {
            return this.mXpAudioPloicy.isBtHeadPhoneOn();
        } else {
            return false;
        }
    }

    public int selectAlarmChannels(int location, int fadeTimeMs, int soundid) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.selectAlarmChannels(location, fadeTimeMs, soundid);
            }
            return -1;
        } else if (this.mXpAudioPloicy != null) {
            return this.mXpAudioPloicy.selectAlarmChannels(location, fadeTimeMs, soundid);
        } else {
            return -1;
        }
    }

    public void checkAlarmVolume() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.checkAlarmVolume();
            }
        } else if (this.mXpAudioPloicy != null) {
            this.mXpAudioPloicy.checkAlarmVolume();
        }
    }

    public void setBtCallOn(boolean enable) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setBtCallOn(enable);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.setBtCallOn(enable);
        }
    }

    public void setBtCallOnFlag(int flag) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setBtCallOnFlag(flag);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.setBtCallOnFlag(flag);
        }
    }

    public int getBtCallOnFlag() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.getBtCallOnFlag();
            }
            return 0;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.getBtCallOnFlag();
        } else {
            return 0;
        }
    }

    public boolean isBtCallOn() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.isBtCallOn();
            }
            return false;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.isBtCallOn();
        } else {
            return false;
        }
    }

    public void setBtCallMode(int mode) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setBtCallMode(mode);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.setBtCallMode(mode);
        }
    }

    public int getBtCallMode() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.getBtCallMode();
            }
            return 0;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.getBtCallMode();
        } else {
            return 0;
        }
    }

    public void setKaraokeOn(boolean on) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setKaraokeOn(on);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.setKaraokeOn(on);
        }
    }

    public boolean isKaraokeOn() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.isKaraokeOn();
            }
            return false;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.isKaraokeOn();
        } else {
            return false;
        }
    }

    public boolean isOtherSessionOn() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.isOtherSessionOn();
            }
            return false;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.isOtherSessionOn();
        } else {
            return false;
        }
    }

    public String getOtherMusicPlayingPkgs() {
        List<String> pkgList = null;
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                pkgList = this.mXuiAudioPolicyTrigger.getOtherMusicPlayingPkgs();
            }
        } else if (this.mXpAudio != null) {
            pkgList = this.mXpAudio.getOtherMusicPlayingPkgs();
        }
        if (pkgList != null && pkgList.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pkgList.size(); i++) {
                sb.append(pkgList.get(i));
                sb.append(";");
            }
            return sb.toString();
        }
        return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
    }

    public boolean isFmOn() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.isFmOn();
            }
            return false;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.isFmOn();
        } else {
            return false;
        }
    }

    public void setVoiceStatus(int status) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setVoiceStatus(status);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.setVoiceStatus(status);
        }
    }

    public int getVoiceStatus() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.getVoiceStatus();
            }
            return 0;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.getVoiceStatus();
        } else {
            return 0;
        }
    }

    public void setVoicePosition(int position, int flag, String pkgName) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setVoicePosition(position, flag, pkgName);
            }
        } else if (this.mXpAudioPloicy != null) {
            this.mXpAudioPloicy.setVoicePosition(position, flag, pkgName);
        }
    }

    public int getVoicePosition() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.getVoicePosition();
            }
            return 0;
        } else if (this.mXpAudioPloicy != null) {
            return this.mXpAudioPloicy.getVoicePosition();
        } else {
            return 0;
        }
    }

    public boolean setFixedVolume(boolean enable, int vol, int streamType, String callingPackage) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setFixedVolume(enable, vol, streamType, callingPackage);
                return false;
            }
            return false;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.setFixedVolume(enable, vol, streamType, callingPackage);
        } else {
            return false;
        }
    }

    public boolean isFixedVolume(int streamType) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.isFixedVolume(streamType);
            }
            return false;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.isFixedVolume(streamType);
        } else {
            return false;
        }
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
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setVolumeFaded(StreamType, vol, fadetime, callingPackage);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.setVolumeFaded(StreamType, vol, fadetime, callingPackage);
        }
    }

    public void forceChangeToAmpChannel(int channelBits, int activeBits, int volume, boolean stop) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.forceChangeToAmpChannel(channelBits, activeBits, volume, stop);
            }
        } else if (this.mXpAudioPloicy != null) {
            this.mXpAudioPloicy.forceChangeToAmpChannel(channelBits, activeBits, volume, stop);
        }
    }

    public void restoreMusicVolume(String callingPackage) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.restoreMusicVolume(callingPackage);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.restoreMusicVolume(callingPackage);
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
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setDangerousTtsStatus(on);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.setDangerousTtsStatus(on);
        }
    }

    public int getDangerousTtsStatus() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.getDangerousTtsStatus();
            }
            return 0;
        } else if (this.mXpAudio != null) {
            return this.mXpAudio.getDangerousTtsStatus();
        } else {
            return 0;
        }
    }

    public void setDangerousTtsVolLevel(int level) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.setDangerousTtsVolLevel(level);
            }
        } else if (this.mXpAudioPloicy != null) {
            this.mXpAudioPloicy.setDangerousTtsVolLevel(level);
        }
    }

    public int getDangerousTtsVolLevel() {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                return this.mXuiAudioPolicyTrigger.getDangerousTtsVolLevel();
            }
            return 0;
        } else if (this.mXpAudioPloicy != null) {
            return this.mXpAudioPloicy.getDangerousTtsVolLevel();
        } else {
            return 0;
        }
    }

    public void ChangeChannelByTrack(int usage, int id, boolean start) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.ChangeChannelByTrack(usage, id, start);
            }
        } else if (this.mXpAudioPloicy != null) {
            this.mXpAudioPloicy.ChangeChannelByTrack(usage, id, start);
        }
    }

    public void temporaryChangeVolumeDown(int StreamType, int dstVol, boolean restoreVol, int flag, String packageName) {
        if (newPolicyOpen) {
            if (this.mXuiAudioPolicyTrigger != null) {
                this.mXuiAudioPolicyTrigger.temporaryChangeVolumeDown(StreamType, dstVol, restoreVol, flag, packageName);
            }
        } else if (this.mXpAudio != null) {
            this.mXpAudio.temporaryChangeVolumeDown(StreamType, dstVol, restoreVol, flag, packageName);
        }
    }

    public void applyAlarmId(int usage, int id) {
        if (newPolicyOpen && this.mXuiAudioPolicyTrigger != null) {
            this.mXuiAudioPolicyTrigger.applyAlarmId(usage, id);
        }
    }

    public void audioThreadProcess(final int type, final int usage, final int streamType, final int Ppid, final String pkgName) {
        if (mFixedThreadExecutor != null) {
            mFixedThreadExecutor.execute(new Runnable() { // from class: com.android.server.audio.AudioService.8
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
            if (this.mXpAudioPloicy != null) {
                this.mXpAudioPloicy.registerAudioListener(mXpAudioEventListener);
            }
        }
        IBinder listenerBinder = callBackFunc.asBinder();
        if (this.mListenersMap.containsKey(listenerBinder)) {
            return;
        }
        AudioDeathRecipient deathRecipient = new AudioDeathRecipient(listenerBinder, pkgName);
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

    /* JADX INFO: Access modifiers changed from: private */
    public void sendRecordStateBroadcast(boolean start, String pkgName) {
        Log.i(TAG, "sendRecordStateBroadcast " + start + " " + pkgName);
        Intent RecordStateBroadcast = new Intent("xiaopeng.record.using.Action");
        RecordStateBroadcast.putExtra(RECORDSTATE_ACTIOIN_STATUS, start ? "start" : "stop");
        RecordStateBroadcast.putExtra(RECORDSTATE_ACTIOIN_PKG, pkgName);
        this.mContext.sendBroadcast(RecordStateBroadcast);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class AudioDeathRecipient implements IBinder.DeathRecipient {
        private static final String TAG = "AudioDeathRecipient";
        private IBinder mListenerBinder;
        private String mPkgName;

        AudioDeathRecipient(IBinder listenerBinder, String pkgName) {
            Log.d(TAG, "AudioDeathRecipient() " + listenerBinder + " " + pkgName);
            this.mListenerBinder = listenerBinder;
            this.mPkgName = pkgName;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            Log.w(TAG, "binderDied " + this.mListenerBinder + " " + this.mPkgName);
            AudioService.this.unregisterListenerLocked(this.mListenerBinder);
            if (this.mPkgName != null && !this.mPkgName.equals(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS)) {
                AudioService.this.sendRecordStateBroadcast(false, this.mPkgName);
            }
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
