package com.android.server.inputmethod;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityThread;
import android.app.AlertDialog;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManagerInternal;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.LocaleList;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.style.SuggestionSpan;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.LruCache;
import android.util.Pair;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;
import android.view.IWindowManager;
import android.view.InputChannel;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputConnectionInspector;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.view.inputmethod.InputMethodSystemProperty;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;
import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.inputmethod.IInputContentUriToken;
import com.android.internal.inputmethod.IInputMethodPrivilegedOperations;
import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.TransferPipe;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethod;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.view.IInputSessionCallback;
import com.android.internal.view.InputBindResult;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.inputmethod.InputMethodManagerService;
import com.android.server.inputmethod.InputMethodSubtypeSwitchingController;
import com.android.server.inputmethod.InputMethodUtils;
import com.android.server.statusbar.StatusBarManagerService;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.SharedDisplayContainer;
import com.android.server.wm.WindowManagerInternal;
import com.xiaopeng.server.input.xpInputManagerService;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.InvalidParameterException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/* loaded from: classes.dex */
public class InputMethodManagerService extends IInputMethodManager.Stub implements ServiceConnection, Handler.Callback {
    private static final String ACTION_SHOW_INPUT_METHOD_PICKER = "com.android.server.inputmethod.InputMethodManagerService.SHOW_INPUT_METHOD_PICKER";
    static final boolean DEBUG = true;
    private static final int FALLBACK_DISPLAY_ID = 0;
    private static final int IME_CONNECTION_BIND_FLAGS = 1082130437;
    private static final int IME_VISIBLE_BIND_FLAGS = 738725889;
    static final int MSG_APPLY_IME_VISIBILITY = 3070;
    static final int MSG_BIND_CLIENT = 3010;
    static final int MSG_BIND_INPUT = 1010;
    static final int MSG_CREATE_SESSION = 1050;
    static final int MSG_HARD_KEYBOARD_SWITCH_CHANGED = 4000;
    static final int MSG_HIDE_CURRENT_INPUT_METHOD = 1035;
    static final int MSG_HIDE_SOFT_INPUT = 1030;
    static final int MSG_INITIALIZE_IME = 1040;
    static final int MSG_REPORT_FULLSCREEN_MODE = 3045;
    static final int MSG_REPORT_PRE_RENDERED = 3060;
    static final int MSG_SET_ACTIVE = 3020;
    static final int MSG_SET_INTERACTIVE = 3030;
    static final int MSG_SHOW_IM_CONFIG = 3;
    static final int MSG_SHOW_IM_SUBTYPE_ENABLER = 2;
    static final int MSG_SHOW_IM_SUBTYPE_PICKER = 1;
    static final int MSG_SHOW_SOFT_INPUT = 1020;
    static final int MSG_START_INPUT = 2000;
    static final int MSG_SYSTEM_UNLOCK_USER = 5000;
    static final int MSG_UNBIND_CLIENT = 3000;
    static final int MSG_UNBIND_INPUT = 1000;
    private static final int NOT_A_SUBTYPE_ID = -1;
    static final int SECURE_SUGGESTION_SPANS_MAX_SIZE = 20;
    static final String TAG = "InputMethodManagerService";
    private static final String TAG_TRY_SUPPRESSING_IME_SWITCHER = "TrySuppressingImeSwitcher";
    static final long TIME_TO_RECONNECT = 3000;
    static final boolean VDEBUG;
    private boolean mAccessibilityRequestingNoSoftKeyboard;
    private final AppOpsManager mAppOpsManager;
    boolean mBoundToMethod;
    final HandlerCaller mCaller;
    final Context mContext;
    EditorInfo mCurAttribute;
    ClientState mCurClient;
    private boolean mCurClientInKeyguard;
    IBinder mCurFocusedWindow;
    ClientState mCurFocusedWindowClient;
    int mCurFocusedWindowSoftInputMode;
    String mCurId;
    IInputContext mCurInputContext;
    int mCurInputContextMissingMethods;
    Intent mCurIntent;
    IInputMethod mCurMethod;
    String mCurMethodId;
    int mCurSeq;
    IBinder mCurToken;
    private InputMethodSubtype mCurrentSubtype;
    private AlertDialog.Builder mDialogBuilder;
    SessionState mEnabledSession;
    private final int mHardKeyboardBehavior;
    final boolean mHasFeature;
    boolean mHaveConnection;
    private PendingIntent mImeSwitchPendingIntent;
    private Notification.Builder mImeSwitcherNotification;
    int mImeWindowVis;
    private InputMethodInfo[] mIms;
    boolean mInFullscreenMode;
    boolean mInputShown;
    private KeyguardManager mKeyguardManager;
    long mLastBindTime;
    IBinder mLastImeTargetWindow;
    private int mLastSwitchUserId;
    private LocaleList mLastSystemLocales;
    private NotificationManager mNotificationManager;
    private boolean mNotificationShown;
    private String mRequesterName;
    final Resources mRes;
    final InputMethodUtils.InputMethodSettings mSettings;
    boolean mShowExplicitlyRequested;
    boolean mShowForced;
    private boolean mShowImeWithHardKeyboard;
    private boolean mShowOngoingImeSwitcherForPhones;
    boolean mShowRequested;
    private final String mSlotIme;
    private StatusBarManagerService mStatusBar;
    private int[] mSubtypeIds;
    private final InputMethodSubtypeSwitchingController mSwitchingController;
    private AlertDialog mSwitchingDialog;
    private View mSwitchingDialogTitleView;
    boolean mSystemReady;
    private final UserManager mUserManager;
    private final ArrayMap<String, List<InputMethodSubtype>> mAdditionalSubtypeMap = new ArrayMap<>();
    final ArrayList<InputMethodInfo> mMethodList = new ArrayList<>();
    final ArrayMap<String, InputMethodInfo> mMethodMap = new ArrayMap<>();
    private final LruCache<SuggestionSpan, InputMethodInfo> mSecureSuggestionSpans = new LruCache<>(20);
    @GuardedBy({"mMethodMap"})
    private int mMethodMapUpdateCount = 0;
    final ServiceConnection mVisibleConnection = new ServiceConnection() { // from class: com.android.server.inputmethod.InputMethodManagerService.1
        @Override // android.content.ServiceConnection
        public void onBindingDied(ComponentName name) {
            synchronized (InputMethodManagerService.this.mMethodMap) {
                if (InputMethodManagerService.this.mVisibleBound) {
                    InputMethodManagerService.this.mContext.unbindService(InputMethodManagerService.this.mVisibleConnection);
                    InputMethodManagerService.this.mVisibleBound = false;
                }
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
        }
    };
    boolean mVisibleBound = false;
    final ArrayMap<IBinder, ClientState> mClients = new ArrayMap<>();
    private SparseArray<ActivityViewInfo> mActivityViewDisplayIdToParentMap = new SparseArray<>();
    private Matrix mCurActivityViewToScreenMatrix = null;
    int mCurTokenDisplayId = -1;
    boolean mIsInteractive = true;
    int mBackDisposition = 0;
    private IBinder mSwitchingDialogToken = new Binder();
    private final MyPackageMonitor mMyPackageMonitor = new MyPackageMonitor();
    @GuardedBy({"mMethodMap"})
    private final WeakHashMap<IBinder, IBinder> mImeTargetWindowMap = new WeakHashMap<>();
    @GuardedBy({"mMethodMap"})
    private final StartInputHistory mStartInputHistory = new StartInputHistory();
    private final IPackageManager mIPackageManager = AppGlobals.getPackageManager();
    final Handler mHandler = new Handler(this);
    final SettingsObserver mSettingsObserver = new SettingsObserver(this.mHandler);
    final IWindowManager mIWindowManager = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
    final WindowManagerInternal mWindowManagerInternal = (WindowManagerInternal) LocalServices.getService(WindowManagerInternal.class);
    private final DisplayManagerInternal mDisplayManagerInternal = (DisplayManagerInternal) LocalServices.getService(DisplayManagerInternal.class);
    final ImeDisplayValidator mImeDisplayValidator = new ImeDisplayValidator() { // from class: com.android.server.inputmethod.-$$Lambda$InputMethodManagerService$oxpSIwENeEjKtHbxqUXuaXD0Gn8
        @Override // com.android.server.inputmethod.InputMethodManagerService.ImeDisplayValidator
        public final boolean displayCanShowIme(int i) {
            return InputMethodManagerService.this.lambda$new$0$InputMethodManagerService(i);
        }
    };
    private final UserManagerInternal mUserManagerInternal = (UserManagerInternal) LocalServices.getService(UserManagerInternal.class);
    private final HardKeyboardListener mHardKeyboardListener = new HardKeyboardListener();
    private final boolean mIsLowRam = ActivityManager.isLowRamDeviceStatic();

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    private @interface HardKeyboardBehavior {
        public static final int WIRED_AFFORDANCE = 1;
        public static final int WIRELESS_AFFORDANCE = 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @FunctionalInterface
    /* loaded from: classes.dex */
    public interface ImeDisplayValidator {
        boolean displayCanShowIme(int i);
    }

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    private @interface ShellCommandResult {
        public static final int FAILURE = -1;
        public static final int SUCCESS = 0;
    }

    static {
        VDEBUG = SystemProperties.getInt("persist.debug.input", 0) == 1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class DebugFlag {
        private static final Object LOCK = new Object();
        private final boolean mDefaultValue;
        private final String mKey;
        @GuardedBy({"LOCK"})
        private boolean mValue;

        public DebugFlag(String key, boolean defaultValue) {
            this.mKey = key;
            this.mDefaultValue = defaultValue;
            this.mValue = SystemProperties.getBoolean(key, defaultValue);
        }

        void refresh() {
            synchronized (LOCK) {
                this.mValue = SystemProperties.getBoolean(this.mKey, this.mDefaultValue);
            }
        }

        boolean value() {
            boolean z;
            synchronized (LOCK) {
                z = this.mValue;
            }
            return z;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class DebugFlags {
        static final DebugFlag FLAG_OPTIMIZE_START_INPUT = new DebugFlag("debug.optimize_startinput", false);
        static final DebugFlag FLAG_PRE_RENDER_IME_VIEWS = new DebugFlag("persist.pre_render_ime_views", false);

        private DebugFlags() {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class SessionState {
        InputChannel channel;
        final ClientState client;
        final IInputMethod method;
        IInputMethodSession session;

        public String toString() {
            return "SessionState{uid " + this.client.uid + " pid " + this.client.pid + " method " + Integer.toHexString(System.identityHashCode(this.method)) + " session " + Integer.toHexString(System.identityHashCode(this.session)) + " channel " + this.channel + "}";
        }

        SessionState(ClientState _client, IInputMethod _method, IInputMethodSession _session, InputChannel _channel) {
            this.client = _client;
            this.method = _method;
            this.session = _session;
            this.channel = _channel;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class ClientDeathRecipient implements IBinder.DeathRecipient {
        private final IInputMethodClient mClient;
        private final InputMethodManagerService mImms;

        ClientDeathRecipient(InputMethodManagerService imms, IInputMethodClient client) {
            this.mImms = imms;
            this.mClient = client;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            this.mImms.removeClient(this.mClient);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class ClientState {
        final InputBinding binding;
        final IInputMethodClient client;
        final ClientDeathRecipient clientDeathRecipient;
        SessionState curSession;
        final IInputContext inputContext;
        final int pid;
        final int selfReportedDisplayId;
        boolean sessionRequested;
        boolean shouldPreRenderIme;
        final int uid;

        public String toString() {
            return "ClientState{" + Integer.toHexString(System.identityHashCode(this)) + " uid=" + this.uid + " pid=" + this.pid + " displayId=" + this.selfReportedDisplayId + "}";
        }

        ClientState(IInputMethodClient _client, IInputContext _inputContext, int _uid, int _pid, int _selfReportedDisplayId, ClientDeathRecipient _clientDeathRecipient) {
            this.client = _client;
            this.inputContext = _inputContext;
            this.uid = _uid;
            this.pid = _pid;
            this.selfReportedDisplayId = _selfReportedDisplayId;
            this.binding = new InputBinding(null, this.inputContext.asBinder(), this.uid, this.pid);
            this.clientDeathRecipient = _clientDeathRecipient;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class ActivityViewInfo {
        private final Matrix mMatrix;
        private final ClientState mParentClient;

        ActivityViewInfo(ClientState parentClient, Matrix matrix) {
            this.mParentClient = parentClient;
            this.mMatrix = matrix;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class StartInputInfo {
        private static final AtomicInteger sSequenceNumber = new AtomicInteger(0);
        final int mClientBindSequenceNumber;
        final EditorInfo mEditorInfo;
        final int mImeDisplayId;
        final String mImeId;
        final IBinder mImeToken;
        final int mImeUserId;
        final boolean mRestarting;
        final int mStartInputReason;
        final int mTargetDisplayId;
        final int mTargetUserId;
        final IBinder mTargetWindow;
        final int mTargetWindowSoftInputMode;
        final int mSequenceNumber = sSequenceNumber.getAndIncrement();
        final long mTimestamp = SystemClock.uptimeMillis();
        final long mWallTime = System.currentTimeMillis();

        StartInputInfo(int imeUserId, IBinder imeToken, int imeDisplayId, String imeId, int startInputReason, boolean restarting, int targetUserId, int targetDisplayId, IBinder targetWindow, EditorInfo editorInfo, int targetWindowSoftInputMode, int clientBindSequenceNumber) {
            this.mImeUserId = imeUserId;
            this.mImeToken = imeToken;
            this.mImeDisplayId = imeDisplayId;
            this.mImeId = imeId;
            this.mStartInputReason = startInputReason;
            this.mRestarting = restarting;
            this.mTargetUserId = targetUserId;
            this.mTargetDisplayId = targetDisplayId;
            this.mTargetWindow = targetWindow;
            this.mEditorInfo = editorInfo;
            this.mTargetWindowSoftInputMode = targetWindowSoftInputMode;
            this.mClientBindSequenceNumber = clientBindSequenceNumber;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class StartInputHistory {
        private static final int ENTRY_SIZE_FOR_HIGH_RAM_DEVICE = 16;
        private static final int ENTRY_SIZE_FOR_LOW_RAM_DEVICE = 5;
        private final Entry[] mEntries;
        private int mNextIndex;

        private StartInputHistory() {
            this.mEntries = new Entry[getEntrySize()];
            this.mNextIndex = 0;
        }

        private static int getEntrySize() {
            if (ActivityManager.isLowRamDeviceStatic()) {
                return 5;
            }
            return 16;
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes.dex */
        public static final class Entry {
            int mClientBindSequenceNumber;
            EditorInfo mEditorInfo;
            int mImeDisplayId;
            String mImeId;
            String mImeTokenString;
            int mImeUserId;
            boolean mRestarting;
            int mSequenceNumber;
            int mStartInputReason;
            int mTargetDisplayId;
            int mTargetUserId;
            int mTargetWindowSoftInputMode;
            String mTargetWindowString;
            long mTimestamp;
            long mWallTime;

            Entry(StartInputInfo original) {
                set(original);
            }

            void set(StartInputInfo original) {
                this.mSequenceNumber = original.mSequenceNumber;
                this.mTimestamp = original.mTimestamp;
                this.mWallTime = original.mWallTime;
                this.mImeUserId = original.mImeUserId;
                this.mImeTokenString = String.valueOf(original.mImeToken);
                this.mImeDisplayId = original.mImeDisplayId;
                this.mImeId = original.mImeId;
                this.mStartInputReason = original.mStartInputReason;
                this.mRestarting = original.mRestarting;
                this.mTargetUserId = original.mTargetUserId;
                this.mTargetDisplayId = original.mTargetDisplayId;
                this.mTargetWindowString = String.valueOf(original.mTargetWindow);
                this.mEditorInfo = original.mEditorInfo;
                this.mTargetWindowSoftInputMode = original.mTargetWindowSoftInputMode;
                this.mClientBindSequenceNumber = original.mClientBindSequenceNumber;
            }
        }

        void addEntry(StartInputInfo info) {
            int index = this.mNextIndex;
            Entry[] entryArr = this.mEntries;
            if (entryArr[index] == null) {
                entryArr[index] = new Entry(info);
            } else {
                entryArr[index].set(info);
            }
            this.mNextIndex = (this.mNextIndex + 1) % this.mEntries.length;
        }

        void dump(PrintWriter pw, String prefix) {
            SimpleDateFormat dataFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
            int i = 0;
            while (true) {
                Entry[] entryArr = this.mEntries;
                if (i < entryArr.length) {
                    Entry entry = entryArr[(this.mNextIndex + i) % entryArr.length];
                    if (entry != null) {
                        pw.print(prefix);
                        pw.println("StartInput #" + entry.mSequenceNumber + ":");
                        pw.print(prefix);
                        pw.println(" time=" + dataFormat.format(new Date(entry.mWallTime)) + " (timestamp=" + entry.mTimestamp + ") reason=" + InputMethodDebug.startInputReasonToString(entry.mStartInputReason) + " restarting=" + entry.mRestarting);
                        pw.print(prefix);
                        StringBuilder sb = new StringBuilder();
                        sb.append(" imeToken=");
                        sb.append(entry.mImeTokenString);
                        sb.append(" [");
                        sb.append(entry.mImeId);
                        sb.append("]");
                        pw.print(sb.toString());
                        pw.print(" imeUserId=" + entry.mImeUserId);
                        pw.println(" imeDisplayId=" + entry.mImeDisplayId);
                        pw.print(prefix);
                        pw.println(" targetWin=" + entry.mTargetWindowString + " [" + entry.mEditorInfo.packageName + "] targetUserId=" + entry.mTargetUserId + " targetDisplayId=" + entry.mTargetDisplayId + " clientBindSeq=" + entry.mClientBindSequenceNumber);
                        pw.print(prefix);
                        StringBuilder sb2 = new StringBuilder();
                        sb2.append(" softInputMode=");
                        sb2.append(InputMethodDebug.softInputModeToString(entry.mTargetWindowSoftInputMode));
                        pw.println(sb2.toString());
                        pw.print(prefix);
                        pw.println(" inputType=0x" + Integer.toHexString(entry.mEditorInfo.inputType) + " imeOptions=0x" + Integer.toHexString(entry.mEditorInfo.imeOptions) + " fieldId=0x" + Integer.toHexString(entry.mEditorInfo.fieldId) + " fieldName=" + entry.mEditorInfo.fieldName + " actionId=" + entry.mEditorInfo.actionId + " actionLabel=" + ((Object) entry.mEditorInfo.actionLabel));
                    }
                    i++;
                } else {
                    return;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class SettingsObserver extends ContentObserver {
        String mLastEnabled;
        boolean mRegistered;
        int mUserId;

        SettingsObserver(Handler handler) {
            super(handler);
            this.mRegistered = false;
            this.mLastEnabled = "";
        }

        public void registerContentObserverLocked(int userId) {
            if (this.mRegistered && this.mUserId == userId) {
                return;
            }
            ContentResolver resolver = InputMethodManagerService.this.mContext.getContentResolver();
            if (this.mRegistered) {
                InputMethodManagerService.this.mContext.getContentResolver().unregisterContentObserver(this);
                this.mRegistered = false;
            }
            if (this.mUserId != userId) {
                this.mLastEnabled = "";
                this.mUserId = userId;
            }
            resolver.registerContentObserver(Settings.Secure.getUriFor("default_input_method"), false, this, userId);
            resolver.registerContentObserver(Settings.Secure.getUriFor("enabled_input_methods"), false, this, userId);
            resolver.registerContentObserver(Settings.Secure.getUriFor("selected_input_method_subtype"), false, this, userId);
            resolver.registerContentObserver(Settings.Secure.getUriFor("show_ime_with_hard_keyboard"), false, this, userId);
            resolver.registerContentObserver(Settings.Secure.getUriFor("accessibility_soft_keyboard_mode"), false, this, userId);
            this.mRegistered = true;
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            Uri showImeUri = Settings.Secure.getUriFor("show_ime_with_hard_keyboard");
            Uri accessibilityRequestingNoImeUri = Settings.Secure.getUriFor("accessibility_soft_keyboard_mode");
            synchronized (InputMethodManagerService.this.mMethodMap) {
                if (showImeUri.equals(uri)) {
                    InputMethodManagerService.this.updateKeyboardFromSettingsLocked();
                } else if (accessibilityRequestingNoImeUri.equals(uri)) {
                    int accessibilitySoftKeyboardSetting = Settings.Secure.getIntForUser(InputMethodManagerService.this.mContext.getContentResolver(), "accessibility_soft_keyboard_mode", 0, this.mUserId);
                    InputMethodManagerService.this.mAccessibilityRequestingNoSoftKeyboard = (accessibilitySoftKeyboardSetting & 3) == 1;
                    if (InputMethodManagerService.this.mAccessibilityRequestingNoSoftKeyboard) {
                        boolean showRequested = InputMethodManagerService.this.mShowRequested;
                        InputMethodManagerService.this.hideCurrentInputLocked(0, null);
                        InputMethodManagerService.this.mShowRequested = showRequested;
                    } else if (InputMethodManagerService.this.mShowRequested) {
                        InputMethodManagerService.this.showCurrentInputLocked(1, null);
                    }
                } else {
                    boolean enabledChanged = false;
                    String newEnabled = InputMethodManagerService.this.mSettings.getEnabledInputMethodsStr();
                    if (!this.mLastEnabled.equals(newEnabled)) {
                        this.mLastEnabled = newEnabled;
                        enabledChanged = true;
                    }
                    InputMethodManagerService.this.updateInputMethodsFromSettingsLocked(enabledChanged);
                }
            }
        }

        public String toString() {
            return "SettingsObserver{mUserId=" + this.mUserId + " mRegistered=" + this.mRegistered + " mLastEnabled=" + this.mLastEnabled + "}";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ImmsBroadcastReceiverForSystemUser extends BroadcastReceiver {
        private ImmsBroadcastReceiverForSystemUser() {
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("android.intent.action.USER_ADDED".equals(action) || "android.intent.action.USER_REMOVED".equals(action)) {
                InputMethodManagerService.this.updateCurrentProfileIds();
            } else if ("android.intent.action.LOCALE_CHANGED".equals(action)) {
                InputMethodManagerService.this.onActionLocaleChanged();
            } else if (InputMethodManagerService.ACTION_SHOW_INPUT_METHOD_PICKER.equals(action)) {
                InputMethodManagerService.this.mHandler.obtainMessage(1, 1, 0).sendToTarget();
            } else {
                Slog.w(InputMethodManagerService.TAG, "Unexpected intent " + intent);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ImmsBroadcastReceiverForAllUsers extends BroadcastReceiver {
        private ImmsBroadcastReceiverForAllUsers() {
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            int resolvedUserId;
            String action = intent.getAction();
            if ("android.intent.action.CLOSE_SYSTEM_DIALOGS".equals(action)) {
                BroadcastReceiver.PendingResult pendingResult = getPendingResult();
                if (pendingResult == null) {
                    return;
                }
                int senderUserId = pendingResult.getSendingUserId();
                if (senderUserId != -1) {
                    if (!InputMethodSystemProperty.PER_PROFILE_IME_ENABLED) {
                        resolvedUserId = InputMethodManagerService.this.mUserManagerInternal.getProfileParentId(senderUserId);
                    } else {
                        resolvedUserId = senderUserId;
                    }
                    if (resolvedUserId != InputMethodManagerService.this.mSettings.getCurrentUserId()) {
                        return;
                    }
                }
                InputMethodManagerService.this.hideInputMethodMenu();
                return;
            }
            Slog.w(InputMethodManagerService.TAG, "Unexpected intent " + intent);
        }
    }

    void onActionLocaleChanged() {
        synchronized (this.mMethodMap) {
            LocaleList possibleNewLocale = this.mRes.getConfiguration().getLocales();
            if (possibleNewLocale == null || !possibleNewLocale.equals(this.mLastSystemLocales)) {
                buildInputMethodListLocked(true);
                resetDefaultImeLocked(this.mContext);
                updateFromSettingsLocked(true);
                this.mLastSystemLocales = possibleNewLocale;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class MyPackageMonitor extends PackageMonitor {
        @GuardedBy({"mMethodMap"})
        private final ArraySet<String> mKnownImePackageNames = new ArraySet<>();
        private final ArrayList<String> mChangedPackages = new ArrayList<>();
        private boolean mImePackageAppeared = false;

        MyPackageMonitor() {
        }

        @GuardedBy({"mMethodMap"})
        void clearKnownImePackageNamesLocked() {
            this.mKnownImePackageNames.clear();
        }

        @GuardedBy({"mMethodMap"})
        final void addKnownImePackageNameLocked(String packageName) {
            this.mKnownImePackageNames.add(packageName);
        }

        @GuardedBy({"mMethodMap"})
        private boolean isChangingPackagesOfCurrentUserLocked() {
            int userId = getChangingUserId();
            boolean retval = userId == InputMethodManagerService.this.mSettings.getCurrentUserId();
            if (!retval) {
                Slog.d(InputMethodManagerService.TAG, "--- ignore this call back from a background user: " + userId);
            }
            return retval;
        }

        public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
            synchronized (InputMethodManagerService.this.mMethodMap) {
                if (isChangingPackagesOfCurrentUserLocked()) {
                    String curInputMethodId = InputMethodManagerService.this.mSettings.getSelectedInputMethod();
                    int N = InputMethodManagerService.this.mMethodList.size();
                    if (curInputMethodId != null) {
                        for (int i = 0; i < N; i++) {
                            InputMethodInfo imi = InputMethodManagerService.this.mMethodList.get(i);
                            if (imi.getId().equals(curInputMethodId)) {
                                for (String pkg : packages) {
                                    if (imi.getPackageName().equals(pkg)) {
                                        if (doit) {
                                            InputMethodManagerService.this.resetSelectedInputMethodAndSubtypeLocked("");
                                            InputMethodManagerService.this.chooseNewDefaultIMELocked();
                                            return true;
                                        }
                                        return true;
                                    }
                                }
                                continue;
                            }
                        }
                    }
                    return false;
                }
                return false;
            }
        }

        public void onBeginPackageChanges() {
            clearPackageChangeState();
        }

        public void onPackageAppeared(String packageName, int reason) {
            if (!this.mImePackageAppeared) {
                PackageManager pm = InputMethodManagerService.this.mContext.getPackageManager();
                List<ResolveInfo> services = pm.queryIntentServicesAsUser(new Intent("android.view.InputMethod").setPackage(packageName), 512, getChangingUserId());
                if (!services.isEmpty()) {
                    this.mImePackageAppeared = true;
                }
            }
            this.mChangedPackages.add(packageName);
        }

        public void onPackageDisappeared(String packageName, int reason) {
            this.mChangedPackages.add(packageName);
        }

        public void onPackageModified(String packageName) {
            this.mChangedPackages.add(packageName);
        }

        public void onPackagesSuspended(String[] packages) {
            for (String packageName : packages) {
                this.mChangedPackages.add(packageName);
            }
        }

        public void onPackagesUnsuspended(String[] packages) {
            for (String packageName : packages) {
                this.mChangedPackages.add(packageName);
            }
        }

        public void onFinishPackageChanges() {
            onFinishPackageChangesInternal();
            clearPackageChangeState();
        }

        private void clearPackageChangeState() {
            this.mChangedPackages.clear();
            this.mImePackageAppeared = false;
        }

        @GuardedBy({"mMethodMap"})
        private boolean shouldRebuildInputMethodListLocked() {
            if (this.mImePackageAppeared) {
                return true;
            }
            int N = this.mChangedPackages.size();
            for (int i = 0; i < N; i++) {
                String packageName = this.mChangedPackages.get(i);
                if (this.mKnownImePackageNames.contains(packageName)) {
                    return true;
                }
            }
            return false;
        }

        private void onFinishPackageChangesInternal() {
            int change;
            synchronized (InputMethodManagerService.this.mMethodMap) {
                if (isChangingPackagesOfCurrentUserLocked()) {
                    if (shouldRebuildInputMethodListLocked()) {
                        InputMethodInfo curIm = null;
                        String curInputMethodId = InputMethodManagerService.this.mSettings.getSelectedInputMethod();
                        int N = InputMethodManagerService.this.mMethodList.size();
                        if (curInputMethodId != null) {
                            for (int i = 0; i < N; i++) {
                                InputMethodInfo imi = InputMethodManagerService.this.mMethodList.get(i);
                                String imiId = imi.getId();
                                if (imiId.equals(curInputMethodId)) {
                                    curIm = imi;
                                }
                                int change2 = isPackageDisappearing(imi.getPackageName());
                                if (isPackageModified(imi.getPackageName())) {
                                    InputMethodManagerService.this.mAdditionalSubtypeMap.remove(imi.getId());
                                    AdditionalSubtypeUtils.save(InputMethodManagerService.this.mAdditionalSubtypeMap, InputMethodManagerService.this.mMethodMap, InputMethodManagerService.this.mSettings.getCurrentUserId());
                                }
                                if (change2 == 2 || change2 == 3) {
                                    Slog.i(InputMethodManagerService.TAG, "Input method uninstalled, disabling: " + imi.getComponent());
                                    InputMethodManagerService.this.setInputMethodEnabledLocked(imi.getId(), false);
                                }
                            }
                        }
                        InputMethodManagerService.this.buildInputMethodListLocked(false);
                        boolean changed = false;
                        if (curIm != null && ((change = isPackageDisappearing(curIm.getPackageName())) == 2 || change == 3)) {
                            ServiceInfo si = null;
                            try {
                                si = InputMethodManagerService.this.mIPackageManager.getServiceInfo(curIm.getComponent(), 0, InputMethodManagerService.this.mSettings.getCurrentUserId());
                            } catch (RemoteException e) {
                            }
                            if (si == null) {
                                Slog.i(InputMethodManagerService.TAG, "Current input method removed: " + curInputMethodId);
                                InputMethodManagerService.this.updateSystemUiLocked(0, InputMethodManagerService.this.mBackDisposition);
                                if (!InputMethodManagerService.this.chooseNewDefaultIMELocked()) {
                                    changed = true;
                                    curIm = null;
                                    Slog.i(InputMethodManagerService.TAG, "Unsetting current input method");
                                    InputMethodManagerService.this.resetSelectedInputMethodAndSubtypeLocked("");
                                }
                            }
                        }
                        if (curIm == null) {
                            changed = InputMethodManagerService.this.chooseNewDefaultIMELocked();
                        } else if (!changed && isPackageModified(curIm.getPackageName())) {
                            changed = true;
                        }
                        if (changed) {
                            InputMethodManagerService.this.updateFromSettingsLocked(false);
                        }
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class MethodCallback extends IInputSessionCallback.Stub {
        private final InputChannel mChannel;
        private final IInputMethod mMethod;
        private final InputMethodManagerService mParentIMMS;

        MethodCallback(InputMethodManagerService imms, IInputMethod method, InputChannel channel) {
            this.mParentIMMS = imms;
            this.mMethod = method;
            this.mChannel = channel;
        }

        public void sessionCreated(IInputMethodSession session) {
            long ident = Binder.clearCallingIdentity();
            try {
                this.mParentIMMS.onSessionCreated(this.mMethod, session, this.mChannel);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class HardKeyboardListener implements WindowManagerInternal.OnHardKeyboardStatusChangeListener {
        private HardKeyboardListener() {
        }

        @Override // com.android.server.wm.WindowManagerInternal.OnHardKeyboardStatusChangeListener
        public void onHardKeyboardStatusChange(boolean available) {
            InputMethodManagerService.this.mHandler.sendMessage(InputMethodManagerService.this.mHandler.obtainMessage(InputMethodManagerService.MSG_HARD_KEYBOARD_SWITCH_CHANGED, Integer.valueOf(available ? 1 : 0)));
        }

        public void handleHardKeyboardStatusChange(boolean available) {
            Slog.w(InputMethodManagerService.TAG, "HardKeyboardStatusChanged: available=" + available);
            synchronized (InputMethodManagerService.this.mMethodMap) {
                if (InputMethodManagerService.this.mSwitchingDialog != null && InputMethodManagerService.this.mSwitchingDialogTitleView != null && InputMethodManagerService.this.mSwitchingDialog.isShowing()) {
                    InputMethodManagerService.this.mSwitchingDialogTitleView.findViewById(16909071).setVisibility(available ? 0 : 8);
                }
            }
        }
    }

    /* loaded from: classes.dex */
    public static final class Lifecycle extends SystemService {
        private InputMethodManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            this.mService = new InputMethodManagerService(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            LocalServices.addService(InputMethodManagerInternal.class, new LocalServiceImpl(this.mService));
            publishBinderService("input_method", this.mService);
        }

        @Override // com.android.server.SystemService
        public void onSwitchUser(int userHandle) {
            this.mService.onSwitchUser(userHandle);
        }

        @Override // com.android.server.SystemService
        public void onBootPhase(int phase) {
            if (phase == 550) {
                StatusBarManagerService statusBarService = (StatusBarManagerService) ServiceManager.getService("statusbar");
                this.mService.systemRunning(statusBarService);
            }
        }

        @Override // com.android.server.SystemService
        public void onUnlockUser(int userHandle) {
            this.mService.mHandler.sendMessage(this.mService.mHandler.obtainMessage(5000, userHandle, 0));
        }
    }

    void onUnlockUser(int userId) {
        synchronized (this.mMethodMap) {
            int currentUserId = this.mSettings.getCurrentUserId();
            Slog.d(TAG, "onUnlockUser: userId=" + userId + " curUserId=" + currentUserId);
            if (userId != currentUserId) {
                return;
            }
            this.mSettings.switchCurrentUser(currentUserId, !this.mSystemReady);
            if (this.mSystemReady) {
                buildInputMethodListLocked(false);
                updateInputMethodsFromSettingsLocked(true);
            }
        }
    }

    void onSwitchUser(int userId) {
        synchronized (this.mMethodMap) {
            switchUserLocked(userId);
        }
    }

    public InputMethodManagerService(Context context) {
        this.mContext = context;
        this.mRes = context.getResources();
        this.mCaller = new HandlerCaller(context, (Looper) null, new HandlerCaller.Callback() { // from class: com.android.server.inputmethod.InputMethodManagerService.2
            public void executeMessage(Message msg) {
                InputMethodManagerService.this.handleMessage(msg);
            }
        }, true);
        this.mAppOpsManager = (AppOpsManager) this.mContext.getSystemService(AppOpsManager.class);
        this.mUserManager = (UserManager) this.mContext.getSystemService(UserManager.class);
        this.mHasFeature = context.getPackageManager().hasSystemFeature("android.software.input_methods");
        this.mSlotIme = this.mContext.getString(17041091);
        this.mHardKeyboardBehavior = this.mContext.getResources().getInteger(17694808);
        Bundle extras = new Bundle();
        extras.putBoolean("android.allowDuringSetup", true);
        int accentColor = this.mContext.getColor(17170460);
        this.mImeSwitcherNotification = new Notification.Builder(this.mContext, SystemNotificationChannels.VIRTUAL_KEYBOARD).setSmallIcon(17302744).setWhen(0L).setOngoing(true).addExtras(extras).setCategory("sys").setColor(accentColor);
        Intent intent = new Intent(ACTION_SHOW_INPUT_METHOD_PICKER).setPackage(this.mContext.getPackageName());
        this.mImeSwitchPendingIntent = PendingIntent.getBroadcast(this.mContext, 0, intent, 0);
        this.mShowOngoingImeSwitcherForPhones = false;
        this.mNotificationShown = false;
        int userId = 0;
        try {
            userId = ActivityManager.getService().getCurrentUser().id;
        } catch (RemoteException e) {
            Slog.w(TAG, "Couldn't get current user ID; guessing it's 0", e);
        }
        this.mLastSwitchUserId = userId;
        this.mSettings = new InputMethodUtils.InputMethodSettings(this.mRes, context.getContentResolver(), this.mMethodMap, userId, !this.mSystemReady);
        updateCurrentProfileIds();
        AdditionalSubtypeUtils.load(this.mAdditionalSubtypeMap, userId);
        this.mSwitchingController = InputMethodSubtypeSwitchingController.createInstanceLocked(this.mSettings, context);
    }

    public /* synthetic */ boolean lambda$new$0$InputMethodManagerService(int displayId) {
        return this.mWindowManagerInternal.shouldShowIme(displayId);
    }

    private void resetDefaultImeLocked(Context context) {
        String str = this.mCurMethodId;
        if (str != null && !this.mMethodMap.get(str).isSystem()) {
            return;
        }
        List<InputMethodInfo> suitableImes = InputMethodUtils.getDefaultEnabledImes(context, this.mSettings.getEnabledInputMethodListLocked());
        if (suitableImes.isEmpty()) {
            Slog.i(TAG, "No default found");
            return;
        }
        InputMethodInfo defIm = suitableImes.get(0);
        Slog.i(TAG, "Default found, using " + defIm.getId());
        setSelectedInputMethodAndSubtypeLocked(defIm, -1, false);
    }

    @GuardedBy({"mMethodMap"})
    private void switchUserLocked(int newUserId) {
        Slog.d(TAG, "Switching user stage 1/3. newUserId=" + newUserId + " currentUserId=" + this.mSettings.getCurrentUserId());
        this.mSettingsObserver.registerContentObserverLocked(newUserId);
        boolean useCopyOnWriteSettings = (this.mSystemReady && this.mUserManagerInternal.isUserUnlockingOrUnlocked(newUserId)) ? false : true;
        this.mSettings.switchCurrentUser(newUserId, useCopyOnWriteSettings);
        updateCurrentProfileIds();
        AdditionalSubtypeUtils.load(this.mAdditionalSubtypeMap, newUserId);
        String defaultImiId = this.mSettings.getSelectedInputMethod();
        Slog.d(TAG, "Switching user stage 2/3. newUserId=" + newUserId + " defaultImiId=" + defaultImiId);
        boolean initialUserSwitch = TextUtils.isEmpty(defaultImiId);
        this.mLastSystemLocales = this.mRes.getConfiguration().getLocales();
        if (this.mSystemReady) {
            hideCurrentInputLocked(0, null);
            resetCurrentMethodAndClient(6);
            buildInputMethodListLocked(initialUserSwitch);
            if (TextUtils.isEmpty(this.mSettings.getSelectedInputMethod())) {
                resetDefaultImeLocked(this.mContext);
            }
            updateFromSettingsLocked(true);
        }
        if (initialUserSwitch) {
            InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(this.mIPackageManager, this.mSettings.getEnabledInputMethodListLocked(), newUserId, this.mContext.getBasePackageName());
        }
        Slog.d(TAG, "Switching user stage 3/3. newUserId=" + newUserId + " selectedIme=" + this.mSettings.getSelectedInputMethod());
        this.mLastSwitchUserId = newUserId;
    }

    void updateCurrentProfileIds() {
        InputMethodUtils.InputMethodSettings inputMethodSettings = this.mSettings;
        inputMethodSettings.setCurrentProfileIds(this.mUserManager.getProfileIdsWithDisabled(inputMethodSettings.getCurrentUserId()));
    }

    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            if (!(e instanceof SecurityException)) {
                Slog.wtf(TAG, "Input Method Manager Crash", e);
            }
            throw e;
        }
    }

    public void systemRunning(StatusBarManagerService statusBar) {
        synchronized (this.mMethodMap) {
            Slog.d(TAG, "--- systemReady");
            if (!this.mSystemReady) {
                this.mSystemReady = true;
                this.mLastSystemLocales = this.mRes.getConfiguration().getLocales();
                int currentUserId = this.mSettings.getCurrentUserId();
                this.mSettings.switchCurrentUser(currentUserId, !this.mUserManagerInternal.isUserUnlockingOrUnlocked(currentUserId));
                this.mKeyguardManager = (KeyguardManager) this.mContext.getSystemService(KeyguardManager.class);
                this.mNotificationManager = (NotificationManager) this.mContext.getSystemService(NotificationManager.class);
                this.mStatusBar = statusBar;
                if (this.mStatusBar != null) {
                    this.mStatusBar.setIconVisibility(this.mSlotIme, false);
                }
                updateSystemUiLocked(this.mImeWindowVis, this.mBackDisposition);
                this.mShowOngoingImeSwitcherForPhones = this.mRes.getBoolean(17891620);
                if (this.mShowOngoingImeSwitcherForPhones) {
                    this.mWindowManagerInternal.setOnHardKeyboardStatusChangeListener(this.mHardKeyboardListener);
                }
                this.mMyPackageMonitor.register(this.mContext, null, UserHandle.ALL, true);
                this.mSettingsObserver.registerContentObserverLocked(currentUserId);
                IntentFilter broadcastFilterForSystemUser = new IntentFilter();
                broadcastFilterForSystemUser.addAction("android.intent.action.USER_ADDED");
                broadcastFilterForSystemUser.addAction("android.intent.action.USER_REMOVED");
                broadcastFilterForSystemUser.addAction("android.intent.action.LOCALE_CHANGED");
                broadcastFilterForSystemUser.addAction(ACTION_SHOW_INPUT_METHOD_PICKER);
                this.mContext.registerReceiver(new ImmsBroadcastReceiverForSystemUser(), broadcastFilterForSystemUser);
                IntentFilter broadcastFilterForAllUsers = new IntentFilter();
                broadcastFilterForAllUsers.addAction("android.intent.action.CLOSE_SYSTEM_DIALOGS");
                this.mContext.registerReceiverAsUser(new ImmsBroadcastReceiverForAllUsers(), UserHandle.ALL, broadcastFilterForAllUsers, null, null);
                String defaultImiId = this.mSettings.getSelectedInputMethod();
                boolean imeSelectedOnBoot = !TextUtils.isEmpty(defaultImiId);
                buildInputMethodListLocked(imeSelectedOnBoot ? false : true);
                updateFromSettingsLocked(true);
                InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(this.mIPackageManager, this.mSettings.getEnabledInputMethodListLocked(), currentUserId, this.mContext.getBasePackageName());
            }
        }
    }

    @GuardedBy({"mMethodMap"})
    private boolean calledFromValidUserLocked() {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getUserId(uid);
        Slog.d(TAG, "--- calledFromForegroundUserOrSystemProcess ? calling uid = " + uid + " system uid = 1000 calling userId = " + userId + ", foreground user id = " + this.mSettings.getCurrentUserId() + ", calling pid = " + Binder.getCallingPid() + InputMethodUtils.getApiCallStack());
        if (uid == 1000 || userId == this.mSettings.getCurrentUserId()) {
            return true;
        }
        if (!InputMethodSystemProperty.PER_PROFILE_IME_ENABLED && this.mSettings.isCurrentProfile(userId)) {
            return true;
        }
        if (this.mContext.checkCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL") == 0) {
            Slog.d(TAG, "--- Access granted because the calling process has the INTERACT_ACROSS_USERS_FULL permission");
            return true;
        }
        Slog.w(TAG, "--- IPC called from background users. Ignore. callers=" + Debug.getCallers(10));
        return false;
    }

    @GuardedBy({"mMethodMap"})
    private boolean calledWithValidTokenLocked(IBinder token) {
        if (token == null) {
            throw new InvalidParameterException("token must not be null.");
        }
        if (token != this.mCurToken) {
            Slog.e(TAG, "Ignoring " + Debug.getCaller() + " due to an invalid token. uid:" + Binder.getCallingUid() + " token:" + token);
            return false;
        }
        return true;
    }

    @GuardedBy({"mMethodMap"})
    private boolean bindCurrentInputMethodServiceLocked(Intent service, ServiceConnection conn, int flags) {
        if (service == null || conn == null) {
            Slog.e(TAG, "--- bind failed: service = " + service + ", conn = " + conn);
            return false;
        }
        return this.mContext.bindServiceAsUser(service, conn, flags, new UserHandle(this.mSettings.getCurrentUserId()));
    }

    public List<InputMethodInfo> getInputMethodList(int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            this.mContext.enforceCallingPermission("android.permission.INTERACT_ACROSS_USERS_FULL", null);
        }
        synchronized (this.mMethodMap) {
            int[] resolvedUserIds = InputMethodUtils.resolveUserId(userId, this.mSettings.getCurrentUserId(), null);
            if (resolvedUserIds.length != 1) {
                return Collections.emptyList();
            }
            long ident = Binder.clearCallingIdentity();
            List<InputMethodInfo> inputMethodListLocked = getInputMethodListLocked(resolvedUserIds[0]);
            Binder.restoreCallingIdentity(ident);
            return inputMethodListLocked;
        }
    }

    public List<InputMethodInfo> getEnabledInputMethodList(int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            this.mContext.enforceCallingPermission("android.permission.INTERACT_ACROSS_USERS_FULL", null);
        }
        synchronized (this.mMethodMap) {
            int[] resolvedUserIds = InputMethodUtils.resolveUserId(userId, this.mSettings.getCurrentUserId(), null);
            if (resolvedUserIds.length != 1) {
                return Collections.emptyList();
            }
            long ident = Binder.clearCallingIdentity();
            List<InputMethodInfo> enabledInputMethodListLocked = getEnabledInputMethodListLocked(resolvedUserIds[0]);
            Binder.restoreCallingIdentity(ident);
            return enabledInputMethodListLocked;
        }
    }

    @GuardedBy({"mMethodMap"})
    private List<InputMethodInfo> getInputMethodListLocked(int userId) {
        if (userId == this.mSettings.getCurrentUserId()) {
            return new ArrayList<>(this.mMethodList);
        }
        ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
        ArrayList<InputMethodInfo> methodList = new ArrayList<>();
        ArrayMap<String, List<InputMethodSubtype>> additionalSubtypeMap = new ArrayMap<>();
        AdditionalSubtypeUtils.load(additionalSubtypeMap, userId);
        queryInputMethodServicesInternal(this.mContext, userId, additionalSubtypeMap, methodMap, methodList);
        return methodList;
    }

    @GuardedBy({"mMethodMap"})
    private List<InputMethodInfo> getEnabledInputMethodListLocked(int userId) {
        if (userId == this.mSettings.getCurrentUserId()) {
            return this.mSettings.getEnabledInputMethodListLocked();
        }
        ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
        ArrayList<InputMethodInfo> methodList = new ArrayList<>();
        ArrayMap<String, List<InputMethodSubtype>> additionalSubtypeMap = new ArrayMap<>();
        AdditionalSubtypeUtils.load(additionalSubtypeMap, userId);
        queryInputMethodServicesInternal(this.mContext, userId, additionalSubtypeMap, methodMap, methodList);
        InputMethodUtils.InputMethodSettings settings = new InputMethodUtils.InputMethodSettings(this.mContext.getResources(), this.mContext.getContentResolver(), methodMap, userId, true);
        return settings.getEnabledInputMethodListLocked();
    }

    public List<InputMethodSubtype> getEnabledInputMethodSubtypeList(String imiId, boolean allowsImplicitlySelectedSubtypes) {
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (this.mMethodMap) {
            int[] resolvedUserIds = InputMethodUtils.resolveUserId(callingUserId, this.mSettings.getCurrentUserId(), null);
            if (resolvedUserIds.length != 1) {
                return Collections.emptyList();
            }
            long ident = Binder.clearCallingIdentity();
            List<InputMethodSubtype> enabledInputMethodSubtypeListLocked = getEnabledInputMethodSubtypeListLocked(imiId, allowsImplicitlySelectedSubtypes, resolvedUserIds[0]);
            Binder.restoreCallingIdentity(ident);
            return enabledInputMethodSubtypeListLocked;
        }
    }

    @GuardedBy({"mMethodMap"})
    private List<InputMethodSubtype> getEnabledInputMethodSubtypeListLocked(String imiId, boolean allowsImplicitlySelectedSubtypes, int userId) {
        InputMethodInfo imi;
        String str;
        if (userId == this.mSettings.getCurrentUserId()) {
            if (imiId == null && (str = this.mCurMethodId) != null) {
                imi = this.mMethodMap.get(str);
            } else {
                imi = this.mMethodMap.get(imiId);
            }
            if (imi == null) {
                return Collections.emptyList();
            }
            return this.mSettings.getEnabledInputMethodSubtypeListLocked(this.mContext, imi, allowsImplicitlySelectedSubtypes);
        }
        ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
        ArrayList<InputMethodInfo> methodList = new ArrayList<>();
        ArrayMap<String, List<InputMethodSubtype>> additionalSubtypeMap = new ArrayMap<>();
        AdditionalSubtypeUtils.load(additionalSubtypeMap, userId);
        queryInputMethodServicesInternal(this.mContext, userId, additionalSubtypeMap, methodMap, methodList);
        InputMethodInfo imi2 = methodMap.get(imiId);
        if (imi2 == null) {
            return Collections.emptyList();
        }
        InputMethodUtils.InputMethodSettings settings = new InputMethodUtils.InputMethodSettings(this.mContext.getResources(), this.mContext.getContentResolver(), methodMap, userId, true);
        return settings.getEnabledInputMethodSubtypeListLocked(this.mContext, imi2, allowsImplicitlySelectedSubtypes);
    }

    public void addClient(IInputMethodClient client, IInputContext inputContext, int selfReportedDisplayId) {
        int callerUid = Binder.getCallingUid();
        int callerPid = Binder.getCallingPid();
        synchronized (this.mMethodMap) {
            try {
                int numClients = this.mClients.size();
                for (int i = 0; i < numClients; i++) {
                    ClientState state = this.mClients.valueAt(i);
                    if (state.uid == callerUid && state.pid == callerPid && state.selfReportedDisplayId == selfReportedDisplayId) {
                        throw new SecurityException("uid=" + callerUid + "/pid=" + callerPid + "/displayId=" + selfReportedDisplayId + " is already registered.");
                    }
                }
                try {
                    ClientDeathRecipient deathRecipient = new ClientDeathRecipient(this, client);
                    try {
                        client.asBinder().linkToDeath(deathRecipient, 0);
                        this.mClients.put(client.asBinder(), new ClientState(client, inputContext, callerUid, callerPid, selfReportedDisplayId, deathRecipient));
                    } catch (RemoteException e) {
                        throw new IllegalStateException(e);
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

    void removeClient(IInputMethodClient client) {
        synchronized (this.mMethodMap) {
            ClientState cs = this.mClients.remove(client.asBinder());
            if (cs != null) {
                client.asBinder().unlinkToDeath(cs.clientDeathRecipient, 0);
                clearClientSessionLocked(cs);
                int numItems = this.mActivityViewDisplayIdToParentMap.size();
                for (int i = numItems - 1; i >= 0; i--) {
                    ActivityViewInfo info = this.mActivityViewDisplayIdToParentMap.valueAt(i);
                    if (info.mParentClient == cs) {
                        this.mActivityViewDisplayIdToParentMap.removeAt(i);
                    }
                }
                if (this.mCurClient == cs) {
                    if (this.mBoundToMethod) {
                        this.mBoundToMethod = false;
                        if (this.mCurMethod != null) {
                            executeOrSendMessage(this.mCurMethod, this.mCaller.obtainMessageO(1000, this.mCurMethod));
                        }
                    }
                    this.mCurClient = null;
                    this.mCurActivityViewToScreenMatrix = null;
                }
                if (this.mCurFocusedWindowClient == cs) {
                    this.mCurFocusedWindowClient = null;
                }
            }
        }
    }

    void executeOrSendMessage(IInterface target, Message msg) {
        if (target.asBinder() instanceof Binder) {
            this.mCaller.sendMessage(msg);
            return;
        }
        handleMessage(msg);
        msg.recycle();
    }

    void unbindCurrentClientLocked(int unbindClientReason) {
        if (this.mCurClient != null) {
            Slog.v(TAG, "unbindCurrentInputLocked: client=" + this.mCurClient.client.asBinder());
            if (this.mBoundToMethod) {
                this.mBoundToMethod = false;
                IInputMethod iInputMethod = this.mCurMethod;
                if (iInputMethod != null) {
                    executeOrSendMessage(iInputMethod, this.mCaller.obtainMessageO(1000, iInputMethod));
                }
            }
            executeOrSendMessage(this.mCurClient.client, this.mCaller.obtainMessageIIO((int) MSG_SET_ACTIVE, 0, 0, this.mCurClient));
            executeOrSendMessage(this.mCurClient.client, this.mCaller.obtainMessageIIO((int) MSG_UNBIND_CLIENT, this.mCurSeq, unbindClientReason, this.mCurClient.client));
            this.mCurClient.sessionRequested = false;
            this.mCurClient = null;
            this.mCurActivityViewToScreenMatrix = null;
            hideInputMethodMenuLocked();
        }
    }

    private int getImeShowFlags() {
        if (this.mShowForced) {
            int flags = 0 | 3;
            return flags;
        } else if (!this.mShowExplicitlyRequested) {
            return 0;
        } else {
            int flags2 = 0 | 1;
            return flags2;
        }
    }

    private int getAppShowFlags() {
        if (this.mShowForced) {
            int flags = 0 | 2;
            return flags;
        } else if (this.mShowExplicitlyRequested) {
            return 0;
        } else {
            int flags2 = 0 | 1;
            return flags2;
        }
    }

    @GuardedBy({"mMethodMap"})
    InputBindResult attachNewInputLocked(int startInputReason, boolean initial) {
        if (!this.mBoundToMethod) {
            IInputMethod iInputMethod = this.mCurMethod;
            executeOrSendMessage(iInputMethod, this.mCaller.obtainMessageOO((int) MSG_BIND_INPUT, iInputMethod, this.mCurClient.binding));
            this.mBoundToMethod = true;
        }
        Binder startInputToken = new Binder();
        StartInputInfo info = new StartInputInfo(this.mSettings.getCurrentUserId(), this.mCurToken, this.mCurTokenDisplayId, this.mCurId, startInputReason, !initial ? 1 : 0, UserHandle.getUserId(this.mCurClient.uid), this.mCurClient.selfReportedDisplayId, this.mCurFocusedWindow, this.mCurAttribute, this.mCurFocusedWindowSoftInputMode, this.mCurSeq);
        this.mImeTargetWindowMap.put(startInputToken, this.mCurFocusedWindow);
        this.mStartInputHistory.addEntry(info);
        SessionState session = this.mCurClient.curSession;
        executeOrSendMessage(session.method, this.mCaller.obtainMessageIIOOOO((int) MSG_START_INPUT, this.mCurInputContextMissingMethods, !initial ? 1 : 0, startInputToken, session, this.mCurInputContext, this.mCurAttribute));
        if (this.mShowRequested) {
            Slog.v(TAG, "Attach new input asks to show input");
            showCurrentInputLocked(getAppShowFlags(), null);
        }
        return new InputBindResult(0, session.session, session.channel != null ? session.channel.dup() : null, this.mCurId, this.mCurSeq, this.mCurActivityViewToScreenMatrix);
    }

    private Matrix getActivityViewToScreenMatrixLocked(int clientDisplayId, int imeDisplayId) {
        if (clientDisplayId == imeDisplayId) {
            return null;
        }
        int displayId = clientDisplayId;
        Matrix matrix = null;
        while (true) {
            ActivityViewInfo info = this.mActivityViewDisplayIdToParentMap.get(displayId);
            if (info == null) {
                return null;
            }
            if (matrix != null) {
                matrix.postConcat(info.mMatrix);
            } else {
                matrix = new Matrix(info.mMatrix);
            }
            if (info.mParentClient.selfReportedDisplayId != imeDisplayId) {
                displayId = info.mParentClient.selfReportedDisplayId;
            } else {
                return matrix;
            }
        }
    }

    @GuardedBy({"mMethodMap"})
    InputBindResult startInputUncheckedLocked(ClientState cs, IInputContext inputContext, int missingMethods, EditorInfo attribute, int startInputFlags, int startInputReason) {
        int missingMethods2;
        String str = this.mCurMethodId;
        if (str == null) {
            return InputBindResult.NO_IME;
        }
        if (!this.mSystemReady) {
            return new InputBindResult(7, (IInputMethodSession) null, (InputChannel) null, str, this.mCurSeq, (Matrix) null);
        }
        if (!InputMethodUtils.checkIfPackageBelongsToUid(this.mAppOpsManager, cs.uid, attribute.packageName)) {
            Slog.e(TAG, "Rejecting this client as it reported an invalid package name. uid=" + cs.uid + " package=" + attribute.packageName);
            return InputBindResult.INVALID_PACKAGE_NAME;
        } else if (this.mWindowManagerInternal.isUidAllowedOnDisplay(cs.selfReportedDisplayId, cs.uid)) {
            int displayIdToShowIme = computeImeDisplayIdForTarget(cs.selfReportedDisplayId, this.mImeDisplayValidator);
            if (this.mCurClient != cs) {
                this.mCurClientInKeyguard = isKeyguardLocked();
                unbindCurrentClientLocked(1);
                Slog.v(TAG, "switching to client: client=" + cs.client.asBinder() + " keyguard=" + this.mCurClientInKeyguard);
                if (this.mIsInteractive) {
                    executeOrSendMessage(cs.client, this.mCaller.obtainMessageIO((int) MSG_SET_ACTIVE, 1, cs));
                }
            }
            this.mCurSeq++;
            if (this.mCurSeq <= 0) {
                this.mCurSeq = 1;
            }
            this.mCurClient = cs;
            this.mCurInputContext = inputContext;
            this.mCurActivityViewToScreenMatrix = getActivityViewToScreenMatrixLocked(cs.selfReportedDisplayId, displayIdToShowIme);
            if (cs.selfReportedDisplayId != displayIdToShowIme && this.mCurActivityViewToScreenMatrix == null) {
                missingMethods2 = missingMethods | 8;
            } else {
                missingMethods2 = missingMethods;
            }
            this.mCurInputContextMissingMethods = missingMethods2;
            this.mCurAttribute = attribute;
            String str2 = this.mCurId;
            if (str2 != null && str2.equals(this.mCurMethodId) && displayIdToShowIme == this.mCurTokenDisplayId) {
                if (cs.curSession != null) {
                    return attachNewInputLocked(startInputReason, (startInputFlags & 8) != 0);
                } else if (this.mHaveConnection) {
                    if (this.mCurMethod == null) {
                        if (SystemClock.uptimeMillis() < this.mLastBindTime + 3000) {
                            return new InputBindResult(2, (IInputMethodSession) null, (InputChannel) null, this.mCurId, this.mCurSeq, (Matrix) null);
                        }
                        EventLog.writeEvent((int) EventLogTags.IMF_FORCE_RECONNECT_IME, this.mCurMethodId, Long.valueOf(SystemClock.uptimeMillis() - this.mLastBindTime), 0);
                    } else {
                        requestClientSessionLocked(cs);
                        return new InputBindResult(1, (IInputMethodSession) null, (InputChannel) null, this.mCurId, this.mCurSeq, (Matrix) null);
                    }
                }
            }
            InputMethodInfo info = this.mMethodMap.get(this.mCurMethodId);
            if (info == null) {
                throw new IllegalArgumentException("Unknown id: " + this.mCurMethodId);
            }
            unbindCurrentMethodLocked();
            this.mCurIntent = new Intent("android.view.InputMethod");
            this.mCurIntent.setComponent(info.getComponent());
            this.mCurIntent.putExtra("android.intent.extra.client_label", 17040146);
            this.mCurIntent.putExtra("android.intent.extra.client_intent", PendingIntent.getActivity(this.mContext, 0, new Intent("android.settings.INPUT_METHOD_SETTINGS"), 0));
            if (bindCurrentInputMethodServiceLocked(this.mCurIntent, this, IME_CONNECTION_BIND_FLAGS)) {
                this.mLastBindTime = SystemClock.uptimeMillis();
                this.mHaveConnection = true;
                this.mCurId = info.getId();
                this.mCurToken = new Binder();
                this.mCurTokenDisplayId = displayIdToShowIme;
                try {
                    Slog.v(TAG, "Adding window token: " + this.mCurToken + " for display: " + this.mCurTokenDisplayId);
                    this.mIWindowManager.addWindowToken(this.mCurToken, 2011, this.mCurTokenDisplayId);
                } catch (RemoteException e) {
                }
                return new InputBindResult(2, (IInputMethodSession) null, (InputChannel) null, this.mCurId, this.mCurSeq, (Matrix) null);
            }
            this.mCurIntent = null;
            Slog.w(TAG, "Failure connecting to input method service: " + this.mCurIntent);
            return InputBindResult.IME_NOT_CONNECTED;
        } else {
            return InputBindResult.INVALID_DISPLAY_ID;
        }
    }

    static int computeImeDisplayIdForTarget(int displayId, ImeDisplayValidator checker) {
        if (displayId == 0 || displayId == -1 || !checker.displayCanShowIme(displayId)) {
            return 0;
        }
        return displayId;
    }

    @Override // android.content.ServiceConnection
    public void onServiceConnected(ComponentName name, IBinder service) {
        synchronized (this.mMethodMap) {
            if (this.mCurIntent != null && name.equals(this.mCurIntent.getComponent())) {
                this.mCurMethod = IInputMethod.Stub.asInterface(service);
                if (this.mCurToken == null) {
                    Slog.w(TAG, "Service connected without a token!");
                    unbindCurrentMethodLocked();
                    return;
                }
                Slog.v(TAG, "Initiating attach with token: " + this.mCurToken);
                executeOrSendMessage(this.mCurMethod, this.mCaller.obtainMessageIOO((int) MSG_INITIALIZE_IME, this.mCurTokenDisplayId, this.mCurMethod, this.mCurToken));
                if (this.mCurClient != null) {
                    clearClientSessionLocked(this.mCurClient);
                    requestClientSessionLocked(this.mCurClient);
                }
            }
        }
    }

    void onSessionCreated(IInputMethod method, IInputMethodSession session, InputChannel channel) {
        synchronized (this.mMethodMap) {
            if (this.mCurMethod != null && method != null && this.mCurMethod.asBinder() == method.asBinder() && this.mCurClient != null) {
                clearClientSessionLocked(this.mCurClient);
                this.mCurClient.curSession = new SessionState(this.mCurClient, method, session, channel);
                InputBindResult res = attachNewInputLocked(9, true);
                if (res.method != null) {
                    executeOrSendMessage(this.mCurClient.client, this.mCaller.obtainMessageOO(3010, this.mCurClient.client, res));
                }
                return;
            }
            channel.dispose();
        }
    }

    void unbindCurrentMethodLocked() {
        if (this.mVisibleBound) {
            this.mContext.unbindService(this.mVisibleConnection);
            this.mVisibleBound = false;
        }
        if (this.mHaveConnection) {
            this.mContext.unbindService(this);
            this.mHaveConnection = false;
        }
        if (this.mCurToken != null) {
            try {
                Slog.v(TAG, "Removing window token: " + this.mCurToken + " for display: " + this.mCurTokenDisplayId);
                this.mIWindowManager.removeWindowToken(this.mCurToken, this.mCurTokenDisplayId);
            } catch (RemoteException e) {
            }
            this.mImeWindowVis = 0;
            this.mBackDisposition = 0;
            updateSystemUiLocked(this.mImeWindowVis, this.mBackDisposition);
            this.mCurToken = null;
            this.mCurTokenDisplayId = -1;
        }
        this.mCurId = null;
        clearCurMethodLocked();
    }

    void resetCurrentMethodAndClient(int unbindClientReason) {
        this.mCurMethodId = null;
        unbindCurrentMethodLocked();
        unbindCurrentClientLocked(unbindClientReason);
    }

    void requestClientSessionLocked(ClientState cs) {
        if (!cs.sessionRequested) {
            Slog.v(TAG, "Creating new session for client " + cs);
            InputChannel[] channels = InputChannel.openInputChannelPair(cs.toString());
            cs.sessionRequested = true;
            IInputMethod iInputMethod = this.mCurMethod;
            executeOrSendMessage(iInputMethod, this.mCaller.obtainMessageOOO((int) MSG_CREATE_SESSION, iInputMethod, channels[1], new MethodCallback(this, iInputMethod, channels[0])));
        }
    }

    void clearClientSessionLocked(ClientState cs) {
        finishSessionLocked(cs.curSession);
        cs.curSession = null;
        cs.sessionRequested = false;
    }

    private void finishSessionLocked(SessionState sessionState) {
        if (sessionState != null) {
            if (sessionState.session != null) {
                try {
                    sessionState.session.finishSession();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Session failed to close due to remote exception", e);
                    updateSystemUiLocked(0, this.mBackDisposition);
                }
                sessionState.session = null;
            }
            if (sessionState.channel != null) {
                sessionState.channel.dispose();
                sessionState.channel = null;
            }
        }
    }

    void clearCurMethodLocked() {
        if (this.mCurMethod != null) {
            int numClients = this.mClients.size();
            for (int i = 0; i < numClients; i++) {
                clearClientSessionLocked(this.mClients.valueAt(i));
            }
            finishSessionLocked(this.mEnabledSession);
            this.mEnabledSession = null;
            this.mCurMethod = null;
        }
        StatusBarManagerService statusBarManagerService = this.mStatusBar;
        if (statusBarManagerService != null) {
            statusBarManagerService.setIconVisibility(this.mSlotIme, false);
        }
        this.mInFullscreenMode = false;
    }

    @Override // android.content.ServiceConnection
    public void onServiceDisconnected(ComponentName name) {
        synchronized (this.mMethodMap) {
            Slog.v(TAG, "Service disconnected: " + name + " mCurIntent=" + this.mCurIntent);
            if (this.mCurMethod != null && this.mCurIntent != null && name.equals(this.mCurIntent.getComponent())) {
                clearCurMethodLocked();
                this.mLastBindTime = SystemClock.uptimeMillis();
                this.mShowRequested = this.mInputShown;
                this.mInputShown = false;
                SharedDisplayContainer.InputMethodPolicy.onInputMethodChanged(this.mContext, this.mHandler, this.mInputShown);
                unbindCurrentClientLocked(3);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateStatusIcon(IBinder token, String packageName, int iconId) {
        synchronized (this.mMethodMap) {
            if (calledWithValidTokenLocked(token)) {
                long ident = Binder.clearCallingIdentity();
                if (iconId == 0) {
                    Slog.d(TAG, "hide the small icon for the input method");
                    if (this.mStatusBar != null) {
                        this.mStatusBar.setIconVisibility(this.mSlotIme, false);
                    }
                } else if (packageName != null) {
                    Slog.d(TAG, "show a small icon for the input method");
                    CharSequence contentDescription = null;
                    try {
                        PackageManager packageManager = this.mContext.getPackageManager();
                        contentDescription = packageManager.getApplicationLabel(this.mIPackageManager.getApplicationInfo(packageName, 0, this.mSettings.getCurrentUserId()));
                    } catch (RemoteException e) {
                    }
                    if (this.mStatusBar != null) {
                        this.mStatusBar.setIcon(this.mSlotIme, packageName, iconId, 0, contentDescription != null ? contentDescription.toString() : null);
                        this.mStatusBar.setIconVisibility(this.mSlotIme, true);
                    }
                }
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private boolean shouldShowImeSwitcherLocked(int visibility) {
        KeyguardManager keyguardManager;
        if (this.mShowOngoingImeSwitcherForPhones && this.mSwitchingDialog == null) {
            if ((this.mWindowManagerInternal.isKeyguardShowingAndNotOccluded() && (keyguardManager = this.mKeyguardManager) != null && keyguardManager.isKeyguardSecure()) || (visibility & 1) == 0 || (visibility & 4) != 0) {
                return false;
            }
            if (this.mWindowManagerInternal.isHardKeyboardAvailable()) {
                if (this.mHardKeyboardBehavior == 0) {
                    return true;
                }
            } else if ((visibility & 2) == 0) {
                return false;
            }
            List<InputMethodInfo> imis = this.mSettings.getEnabledInputMethodListLocked();
            int N = imis.size();
            if (N > 2) {
                return true;
            }
            if (N < 1) {
                return false;
            }
            int nonAuxCount = 0;
            int auxCount = 0;
            InputMethodSubtype nonAuxSubtype = null;
            InputMethodSubtype auxSubtype = null;
            for (int i = 0; i < N; i++) {
                InputMethodInfo imi = imis.get(i);
                List<InputMethodSubtype> subtypes = this.mSettings.getEnabledInputMethodSubtypeListLocked(this.mContext, imi, true);
                int subtypeCount = subtypes.size();
                if (subtypeCount == 0) {
                    nonAuxCount++;
                } else {
                    for (int j = 0; j < subtypeCount; j++) {
                        InputMethodSubtype subtype = subtypes.get(j);
                        if (!subtype.isAuxiliary()) {
                            nonAuxCount++;
                            nonAuxSubtype = subtype;
                        } else {
                            auxCount++;
                            auxSubtype = subtype;
                        }
                    }
                }
            }
            if (nonAuxCount > 1 || auxCount > 1) {
                return true;
            }
            if (nonAuxCount == 1 && auxCount == 1) {
                return nonAuxSubtype == null || auxSubtype == null || !((nonAuxSubtype.getLocale().equals(auxSubtype.getLocale()) || auxSubtype.overridesImplicitlyEnabledSubtype() || nonAuxSubtype.overridesImplicitlyEnabledSubtype()) && nonAuxSubtype.containsExtraValueKey(TAG_TRY_SUPPRESSING_IME_SWITCHER));
            }
            return false;
        }
        return false;
    }

    private boolean isKeyguardLocked() {
        KeyguardManager keyguardManager = this.mKeyguardManager;
        return keyguardManager != null && keyguardManager.isKeyguardLocked();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setImeWindowStatus(IBinder token, int vis, int backDisposition) {
        boolean dismissImeOnBackKeyPressed;
        int topFocusedDisplayId = this.mWindowManagerInternal.getTopFocusedDisplayId();
        synchronized (this.mMethodMap) {
            if (calledWithValidTokenLocked(token)) {
                if (this.mCurTokenDisplayId == topFocusedDisplayId || this.mCurTokenDisplayId == 0) {
                    this.mImeWindowVis = vis;
                    this.mBackDisposition = backDisposition;
                    updateSystemUiLocked(vis, backDisposition);
                    if (backDisposition == 1) {
                        dismissImeOnBackKeyPressed = false;
                    } else if (backDisposition == 2) {
                        dismissImeOnBackKeyPressed = true;
                    } else {
                        dismissImeOnBackKeyPressed = (vis & 2) != 0;
                    }
                    this.mWindowManagerInternal.updateInputMethodWindowStatus(token, (vis & 2) != 0, dismissImeOnBackKeyPressed);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void reportStartInput(IBinder token, IBinder startInputToken) {
        synchronized (this.mMethodMap) {
            if (calledWithValidTokenLocked(token)) {
                IBinder targetWindow = this.mImeTargetWindowMap.get(startInputToken);
                if (targetWindow != null && this.mLastImeTargetWindow != targetWindow) {
                    this.mWindowManagerInternal.updateInputMethodTargetWindow(token, targetWindow);
                }
                this.mLastImeTargetWindow = targetWindow;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateSystemUiLocked(int vis, int backDisposition) {
        if (this.mCurToken == null) {
            return;
        }
        Slog.d(TAG, "IME window vis: " + vis + " active: " + (vis & 1) + " inv: " + (vis & 4) + " displayId: " + this.mCurTokenDisplayId);
        long ident = Binder.clearCallingIdentity();
        if (vis != 0) {
            try {
                if (isKeyguardLocked() && !this.mCurClientInKeyguard) {
                    vis = 0;
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
        boolean needsToShowImeSwitcher = shouldShowImeSwitcherLocked(vis);
        if (this.mStatusBar != null) {
            this.mStatusBar.setImeWindowStatus(this.mCurTokenDisplayId, this.mCurToken, vis, backDisposition, needsToShowImeSwitcher);
        }
        InputMethodInfo imi = this.mMethodMap.get(this.mCurMethodId);
        if (imi != null && needsToShowImeSwitcher) {
            CharSequence title = this.mRes.getText(17040997);
            CharSequence summary = InputMethodUtils.getImeAndSubtypeDisplayName(this.mContext, imi, this.mCurrentSubtype);
            this.mImeSwitcherNotification.setContentTitle(title).setContentText(summary).setContentIntent(this.mImeSwitchPendingIntent);
            try {
                if (this.mNotificationManager != null && !this.mIWindowManager.hasNavigationBar(0)) {
                    Slog.d(TAG, "--- show notification: label =  " + ((Object) summary));
                    this.mNotificationManager.notifyAsUser(null, 8, this.mImeSwitcherNotification.build(), UserHandle.ALL);
                    this.mNotificationShown = true;
                }
            } catch (RemoteException e) {
            }
        } else if (this.mNotificationShown && this.mNotificationManager != null) {
            Slog.d(TAG, "--- hide notification");
            this.mNotificationManager.cancelAsUser(null, 8, UserHandle.ALL);
            this.mNotificationShown = false;
        }
    }

    void updateFromSettingsLocked(boolean enabledMayChange) {
        updateInputMethodsFromSettingsLocked(enabledMayChange);
        updateKeyboardFromSettingsLocked();
    }

    void updateInputMethodsFromSettingsLocked(boolean enabledMayChange) {
        if (enabledMayChange) {
            List<InputMethodInfo> enabled = this.mSettings.getEnabledInputMethodListLocked();
            for (int i = 0; i < enabled.size(); i++) {
                InputMethodInfo imm = enabled.get(i);
                try {
                    ApplicationInfo ai = this.mIPackageManager.getApplicationInfo(imm.getPackageName(), 32768, this.mSettings.getCurrentUserId());
                    if (ai != null && ai.enabledSetting == 4) {
                        Slog.d(TAG, "Update state(" + imm.getId() + "): DISABLED_UNTIL_USED -> DEFAULT");
                        this.mIPackageManager.setApplicationEnabledSetting(imm.getPackageName(), 0, 1, this.mSettings.getCurrentUserId(), this.mContext.getBasePackageName());
                    }
                } catch (RemoteException e) {
                }
            }
        }
        String id = this.mSettings.getSelectedInputMethod();
        if (TextUtils.isEmpty(id) && chooseNewDefaultIMELocked()) {
            id = this.mSettings.getSelectedInputMethod();
        }
        if (!TextUtils.isEmpty(id)) {
            try {
                setInputMethodLocked(id, this.mSettings.getSelectedInputMethodSubtypeId(id));
            } catch (IllegalArgumentException e2) {
                Slog.w(TAG, "Unknown input method from prefs: " + id, e2);
                resetCurrentMethodAndClient(5);
            }
        } else {
            resetCurrentMethodAndClient(4);
        }
        this.mSwitchingController.resetCircularListLocked(this.mContext);
    }

    public void updateKeyboardFromSettingsLocked() {
        this.mShowImeWithHardKeyboard = this.mSettings.isShowImeWithHardKeyboardEnabled();
        AlertDialog alertDialog = this.mSwitchingDialog;
        if (alertDialog != null && this.mSwitchingDialogTitleView != null && alertDialog.isShowing()) {
            Switch hardKeySwitch = (Switch) this.mSwitchingDialogTitleView.findViewById(16909072);
            hardKeySwitch.setChecked(this.mShowImeWithHardKeyboard);
        }
    }

    void setInputMethodLocked(String id, int subtypeId) {
        InputMethodSubtype newSubtype;
        InputMethodInfo info = this.mMethodMap.get(id);
        if (info == null) {
            throw new IllegalArgumentException("Unknown id: " + id);
        } else if (id.equals(this.mCurMethodId)) {
            int subtypeCount = info.getSubtypeCount();
            if (subtypeCount <= 0) {
                return;
            }
            InputMethodSubtype oldSubtype = this.mCurrentSubtype;
            if (subtypeId >= 0 && subtypeId < subtypeCount) {
                newSubtype = info.getSubtypeAt(subtypeId);
            } else {
                newSubtype = getCurrentInputMethodSubtypeLocked();
            }
            if (newSubtype == null || oldSubtype == null) {
                Slog.w(TAG, "Illegal subtype state: old subtype = " + oldSubtype + ", new subtype = " + newSubtype);
            } else if (newSubtype != oldSubtype) {
                setSelectedInputMethodAndSubtypeLocked(info, subtypeId, true);
                if (this.mCurMethod != null) {
                    try {
                        updateSystemUiLocked(this.mImeWindowVis, this.mBackDisposition);
                        this.mCurMethod.changeInputMethodSubtype(newSubtype);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to call changeInputMethodSubtype");
                    }
                }
            }
        } else {
            long ident = Binder.clearCallingIdentity();
            try {
                setSelectedInputMethodAndSubtypeLocked(info, subtypeId, false);
                this.mCurMethodId = id;
                if (((ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class)).isSystemReady()) {
                    Intent intent = new Intent("android.intent.action.INPUT_METHOD_CHANGED");
                    intent.addFlags(536870912);
                    intent.putExtra("input_method_id", id);
                    this.mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
                }
                unbindCurrentClientLocked(2);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public boolean showSoftInput(IInputMethodClient client, int flags, ResultReceiver resultReceiver) {
        int uid = Binder.getCallingUid();
        synchronized (this.mMethodMap) {
            if (calledFromValidUserLocked()) {
                long ident = Binder.clearCallingIdentity();
                if (this.mCurClient == null || client == null || this.mCurClient.client.asBinder() != client.asBinder()) {
                    ClientState cs = this.mClients.get(client.asBinder());
                    if (cs == null) {
                        throw new IllegalArgumentException("unknown client " + client.asBinder());
                    } else if (!this.mWindowManagerInternal.isInputMethodClientFocus(cs.uid, cs.pid, cs.selfReportedDisplayId)) {
                        Slog.w(TAG, "Ignoring showSoftInput of uid " + uid + ": " + client);
                        Binder.restoreCallingIdentity(ident);
                        return false;
                    }
                }
                Slog.v(TAG, "Client requesting input be shown");
                boolean showCurrentInputLocked = showCurrentInputLocked(flags, resultReceiver);
                Binder.restoreCallingIdentity(ident);
                return showCurrentInputLocked;
            }
            return false;
        }
    }

    @GuardedBy({"mMethodMap"})
    boolean showCurrentInputLocked(int flags, ResultReceiver resultReceiver) {
        this.mShowRequested = true;
        if (this.mAccessibilityRequestingNoSoftKeyboard) {
            return false;
        }
        if ((flags & 2) != 0) {
            this.mShowExplicitlyRequested = true;
            this.mShowForced = true;
        } else if ((flags & 1) == 0) {
            this.mShowExplicitlyRequested = true;
        }
        if (this.mSystemReady) {
            if (this.mCurMethod != null) {
                String packageName = SharedDisplayContainer.InputMethodPolicy.getRequester();
                SharedDisplayContainer.InputMethodPolicy.onInputMethodShow(this.mContext, packageName);
                Slog.d(TAG, "showCurrentInputLocked: mCurToken=" + this.mCurToken);
                executeOrSendMessage(this.mCurMethod, this.mCaller.obtainMessageIOO((int) MSG_SHOW_SOFT_INPUT, getImeShowFlags(), this.mCurMethod, resultReceiver));
                this.mInputShown = true;
                SharedDisplayContainer.InputMethodPolicy.onInputMethodChanged(this.mContext, this.mHandler, this.mInputShown);
                if (this.mHaveConnection && !this.mVisibleBound) {
                    bindCurrentInputMethodServiceLocked(this.mCurIntent, this.mVisibleConnection, IME_VISIBLE_BIND_FLAGS);
                    this.mVisibleBound = true;
                }
                return true;
            } else if (this.mHaveConnection && SystemClock.uptimeMillis() >= this.mLastBindTime + 3000) {
                EventLog.writeEvent((int) EventLogTags.IMF_FORCE_RECONNECT_IME, this.mCurMethodId, Long.valueOf(SystemClock.uptimeMillis() - this.mLastBindTime), 1);
                Slog.w(TAG, "Force disconnect/connect to the IME in showCurrentInputLocked()");
                this.mContext.unbindService(this);
                bindCurrentInputMethodServiceLocked(this.mCurIntent, this, IME_CONNECTION_BIND_FLAGS);
                return false;
            } else {
                Slog.d(TAG, "Can't show input: connection = " + this.mHaveConnection + ", time = " + ((this.mLastBindTime + 3000) - SystemClock.uptimeMillis()));
                return false;
            }
        }
        return false;
    }

    public boolean hideSoftInput(IInputMethodClient client, int flags, ResultReceiver resultReceiver) {
        int uid = Binder.getCallingUid();
        synchronized (this.mMethodMap) {
            if (calledFromValidUserLocked()) {
                long ident = Binder.clearCallingIdentity();
                if (this.mCurClient == null || client == null || this.mCurClient.client.asBinder() != client.asBinder()) {
                    ClientState cs = this.mClients.get(client.asBinder());
                    if (cs == null) {
                        throw new IllegalArgumentException("unknown client " + client.asBinder());
                    } else if (!this.mWindowManagerInternal.isInputMethodClientFocus(cs.uid, cs.pid, cs.selfReportedDisplayId)) {
                        Slog.w(TAG, "Ignoring hideSoftInput of uid " + uid + ": " + client);
                        Binder.restoreCallingIdentity(ident);
                        return false;
                    }
                }
                Slog.v(TAG, "Client requesting input be hidden");
                boolean hideCurrentInputLocked = hideCurrentInputLocked(flags, resultReceiver);
                Binder.restoreCallingIdentity(ident);
                return hideCurrentInputLocked;
            }
            return false;
        }
    }

    public boolean getInputShown() {
        return this.mInputShown;
    }

    public void forceHideSoftInputMethod() {
        Slog.d(TAG, "forceHideSoftInputMethod() " + this.mInputShown + " mHaveConnection:" + this.mHaveConnection + " mVisibleBound:" + this.mVisibleBound);
        if (this.mInputShown) {
            this.mHandler.removeMessages(MSG_HIDE_CURRENT_INPUT_METHOD);
            this.mHandler.sendEmptyMessage(MSG_HIDE_CURRENT_INPUT_METHOD);
        }
    }

    public String getRequesterName() {
        String str;
        synchronized (this.mMethodMap) {
            str = this.mRequesterName;
        }
        return str;
    }

    public void setRequesterName(String packageName) {
        synchronized (this.mMethodMap) {
            this.mRequesterName = packageName;
        }
    }

    boolean hideCurrentInputLocked(int flags, ResultReceiver resultReceiver) {
        boolean res;
        if ((flags & 1) != 0 && (this.mShowExplicitlyRequested || this.mShowForced)) {
            Slog.v(TAG, "Not hiding: explicit show not cancelled by non-explicit hide");
            return false;
        } else if (this.mShowForced && (flags & 2) != 0) {
            Slog.v(TAG, "Not hiding: forced show not cancelled by not-always hide");
            return false;
        } else {
            boolean z = true;
            if (this.mCurMethod == null || (!this.mInputShown && (this.mImeWindowVis & 1) == 0)) {
                z = false;
            }
            boolean shouldHideSoftInput = z;
            if (shouldHideSoftInput) {
                IInputMethod iInputMethod = this.mCurMethod;
                executeOrSendMessage(iInputMethod, this.mCaller.obtainMessageOO((int) MSG_HIDE_SOFT_INPUT, iInputMethod, resultReceiver));
                res = true;
            } else {
                res = false;
            }
            if (this.mHaveConnection && this.mVisibleBound) {
                this.mContext.unbindService(this.mVisibleConnection);
                this.mVisibleBound = false;
            }
            this.mInputShown = false;
            this.mShowRequested = false;
            this.mShowExplicitlyRequested = false;
            this.mShowForced = false;
            SharedDisplayContainer.InputMethodPolicy.onInputMethodChanged(this.mContext, this.mHandler, this.mInputShown);
            return res;
        }
    }

    public InputBindResult startInputOrWindowGainedFocus(int startInputReason, IInputMethodClient client, IBinder windowToken, int startInputFlags, int softInputMode, int windowFlags, EditorInfo attribute, IInputContext inputContext, int missingMethods, int unverifiedTargetSdkVersion) {
        int userId;
        if (windowToken == null) {
            Slog.e(TAG, "windowToken cannot be null.");
            return InputBindResult.NULL;
        }
        int callingUserId = UserHandle.getCallingUserId();
        if (attribute != null && attribute.targetInputMethodUser != null && attribute.targetInputMethodUser.getIdentifier() != callingUserId) {
            this.mContext.enforceCallingPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "Using EditorInfo.targetInputMethodUser requires INTERACT_ACROSS_USERS_FULL.");
            int userId2 = attribute.targetInputMethodUser.getIdentifier();
            if (this.mUserManagerInternal.isUserRunning(userId2)) {
                userId = userId2;
            } else {
                Slog.e(TAG, "User #" + userId2 + " is not running.");
                return InputBindResult.INVALID_USER;
            }
        } else {
            userId = callingUserId;
        }
        synchronized (this.mMethodMap) {
            try {
                try {
                    long ident = Binder.clearCallingIdentity();
                    InputBindResult result = startInputOrWindowGainedFocusInternalLocked(startInputReason, client, windowToken, startInputFlags, softInputMode, windowFlags, attribute, inputContext, missingMethods, unverifiedTargetSdkVersion, userId);
                    Binder.restoreCallingIdentity(ident);
                    if (result == null) {
                        Slog.wtf(TAG, "InputBindResult is @NonNull. startInputReason=" + InputMethodDebug.startInputReasonToString(startInputReason) + " windowFlags=#" + Integer.toHexString(windowFlags) + " editorInfo=" + attribute);
                        return InputBindResult.NULL;
                    }
                    return result;
                } catch (Throwable th) {
                    th = th;
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                throw th;
            }
        }
    }

    private InputBindResult startInputOrWindowGainedFocusInternalLocked(int startInputReason, IInputMethodClient client, IBinder windowToken, int startInputFlags, int softInputMode, int windowFlags, EditorInfo attribute, IInputContext inputContext, int missingMethods, int unverifiedTargetSdkVersion, int userId) {
        ResultReceiver resultReceiver;
        Slog.v(TAG, "startInputOrWindowGainedFocusInternalLocked: reason=" + InputMethodDebug.startInputReasonToString(startInputReason) + " client=" + client.asBinder() + " inputContext=" + inputContext + " missingMethods=" + InputConnectionInspector.getMissingMethodFlagsAsString(missingMethods) + " attribute=" + attribute + " startInputFlags=" + InputMethodDebug.startInputFlagsToString(startInputFlags) + " softInputMode=" + InputMethodDebug.softInputModeToString(softInputMode) + " windowFlags=#" + Integer.toHexString(windowFlags) + " unverifiedTargetSdkVersion=" + unverifiedTargetSdkVersion);
        String packageName = attribute != null ? attribute.packageName : "";
        SharedDisplayContainer.InputMethodPolicy.onInputMethodShow(this.mContext, packageName);
        int windowDisplayId = this.mWindowManagerInternal.getDisplayIdForWindow(windowToken);
        ClientState cs = this.mClients.get(client.asBinder());
        if (cs == null) {
            throw new IllegalArgumentException("unknown client " + client.asBinder());
        } else if (cs.selfReportedDisplayId != windowDisplayId) {
            Slog.e(TAG, "startInputOrWindowGainedFocusInternal: display ID mismatch. from client:" + cs.selfReportedDisplayId + " from window:" + windowDisplayId);
            return InputBindResult.DISPLAY_ID_MISMATCH;
        } else {
            if (!this.mWindowManagerInternal.isInputMethodClientFocus(cs.uid, cs.pid, cs.selfReportedDisplayId)) {
                Slog.w(TAG, "Focus gain on non-focused client " + cs.client + " (uid=" + cs.uid + " pid=" + cs.pid + ")");
                if (InputMethodManager.dependOnFocus()) {
                    return InputBindResult.NOT_IME_TARGET_WINDOW;
                }
            }
            if (!this.mSettings.isCurrentProfile(userId)) {
                Slog.w(TAG, "A background user is requesting window. Hiding IME.");
                Slog.w(TAG, "If you need to impersonate a foreground user/profile from a background user, use EditorInfo.targetInputMethodUser with INTERACT_ACROSS_USERS_FULL permission.");
                hideCurrentInputLocked(0, null);
                return InputBindResult.INVALID_USER;
            }
            if (InputMethodSystemProperty.PER_PROFILE_IME_ENABLED && userId != this.mSettings.getCurrentUserId()) {
                switchUserLocked(userId);
            }
            Slog.v(TAG, "IME PreRendering MASTER flag: " + DebugFlags.FLAG_PRE_RENDER_IME_VIEWS.value() + ", LowRam: " + this.mIsLowRam);
            cs.shouldPreRenderIme = DebugFlags.FLAG_PRE_RENDER_IME_VIEWS.value() && !this.mIsLowRam;
            if (this.mCurFocusedWindow != windowToken) {
                this.mCurFocusedWindow = windowToken;
                this.mCurFocusedWindowSoftInputMode = softInputMode;
                this.mCurFocusedWindowClient = cs;
                boolean doAutoShow = (softInputMode & 240) == 16 || this.mRes.getConfiguration().isLayoutSizeAtLeast(3);
                boolean isTextEditor = (startInputFlags & 2) != 0;
                boolean didStart = false;
                InputBindResult res = null;
                int i = softInputMode & 15;
                if (i != 0) {
                    if (i != 1) {
                        if (i != 2) {
                            if (i == 3) {
                                Slog.v(TAG, "Window asks to hide input");
                                hideCurrentInputLocked(0, null);
                            } else if (i != 4) {
                                if (i == 5) {
                                    Slog.v(TAG, "Window asks to always show input");
                                    if (!InputMethodUtils.isSoftInputModeStateVisibleAllowed(unverifiedTargetSdkVersion, startInputFlags)) {
                                        Slog.e(TAG, "SOFT_INPUT_STATE_ALWAYS_VISIBLE is ignored because there is no focused view that also returns true from View#onCheckIsTextEditor()");
                                    } else {
                                        if (attribute == null) {
                                            resultReceiver = null;
                                        } else {
                                            resultReceiver = null;
                                            res = startInputUncheckedLocked(cs, inputContext, missingMethods, attribute, startInputFlags, startInputReason);
                                            didStart = true;
                                        }
                                        showCurrentInputLocked(1, resultReceiver);
                                    }
                                }
                            } else if ((softInputMode & 256) != 0) {
                                Slog.v(TAG, "Window asks to show input going forward");
                                if (!InputMethodUtils.isSoftInputModeStateVisibleAllowed(unverifiedTargetSdkVersion, startInputFlags)) {
                                    Slog.e(TAG, "SOFT_INPUT_STATE_VISIBLE is ignored because there is no focused view that also returns true from View#onCheckIsTextEditor()");
                                } else {
                                    if (attribute != null) {
                                        res = startInputUncheckedLocked(cs, inputContext, missingMethods, attribute, startInputFlags, startInputReason);
                                        didStart = true;
                                    }
                                    showCurrentInputLocked(1, null);
                                }
                            }
                        } else if ((softInputMode & 256) != 0) {
                            Slog.v(TAG, "Window asks to hide input going forward");
                            hideCurrentInputLocked(0, null);
                        }
                    }
                } else if (!isTextEditor || !doAutoShow) {
                    if (WindowManager.LayoutParams.mayUseInputMethod(windowFlags)) {
                        Slog.v(TAG, "Unspecified window will hide input");
                        hideCurrentInputLocked(2, null);
                        if (cs.selfReportedDisplayId != this.mCurTokenDisplayId) {
                            unbindCurrentMethodLocked();
                        }
                    }
                } else if (isTextEditor && doAutoShow && (softInputMode & 256) != 0) {
                    Slog.v(TAG, "Unspecified window will show input");
                    if (attribute != null) {
                        res = startInputUncheckedLocked(cs, inputContext, missingMethods, attribute, startInputFlags, startInputReason);
                        didStart = true;
                    }
                    showCurrentInputLocked(1, null);
                }
                if (!didStart) {
                    if (attribute != null) {
                        if (!DebugFlags.FLAG_OPTIMIZE_START_INPUT.value() || (startInputFlags & 2) != 0) {
                            return startInputUncheckedLocked(cs, inputContext, missingMethods, attribute, startInputFlags, startInputReason);
                        }
                        return InputBindResult.NO_EDITOR;
                    }
                    return InputBindResult.NULL_EDITOR_INFO;
                }
                return res;
            }
            Slog.w(TAG, "Window already focused, ignoring focus gain of: " + client + " attribute=" + attribute + ", token = " + windowToken);
            if (attribute != null) {
                return startInputUncheckedLocked(cs, inputContext, missingMethods, attribute, startInputFlags, startInputReason);
            }
            return new InputBindResult(3, (IInputMethodSession) null, (InputChannel) null, (String) null, -1, (Matrix) null);
        }
    }

    private boolean canShowInputMethodPickerLocked(IInputMethodClient client) {
        int uid = Binder.getCallingUid();
        ClientState clientState = this.mCurFocusedWindowClient;
        if (clientState == null || client == null || clientState.client.asBinder() != client.asBinder()) {
            Intent intent = this.mCurIntent;
            return intent != null && InputMethodUtils.checkIfPackageBelongsToUid(this.mAppOpsManager, uid, intent.getComponent().getPackageName());
        }
        return true;
    }

    public void showInputMethodPickerFromClient(IInputMethodClient client, int auxiliarySubtypeMode) {
        synchronized (this.mMethodMap) {
            if (calledFromValidUserLocked()) {
                if (!canShowInputMethodPickerLocked(client)) {
                    Slog.w(TAG, "Ignoring showInputMethodPickerFromClient of uid " + Binder.getCallingUid() + ": " + client);
                    return;
                }
                this.mHandler.sendMessage(this.mCaller.obtainMessageII(1, auxiliarySubtypeMode, this.mCurClient != null ? this.mCurClient.selfReportedDisplayId : 0));
            }
        }
    }

    public void showInputMethodPickerFromSystem(IInputMethodClient client, int auxiliarySubtypeMode, int displayId) {
        if (this.mContext.checkCallingPermission("android.permission.WRITE_SECURE_SETTINGS") != 0) {
            throw new SecurityException("showInputMethodPickerFromSystem requires WRITE_SECURE_SETTINGS permission");
        }
        this.mHandler.sendMessage(this.mCaller.obtainMessageII(1, auxiliarySubtypeMode, displayId));
    }

    public boolean isInputMethodPickerShownForTest() {
        synchronized (this.mMethodMap) {
            if (this.mSwitchingDialog == null) {
                return false;
            }
            return this.mSwitchingDialog.isShowing();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setInputMethod(IBinder token, String id) {
        synchronized (this.mMethodMap) {
            if (calledWithValidTokenLocked(token)) {
                setInputMethodWithSubtypeIdLocked(token, id, -1);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setInputMethodAndSubtype(IBinder token, String id, InputMethodSubtype subtype) {
        synchronized (this.mMethodMap) {
            if (calledWithValidTokenLocked(token)) {
                if (subtype != null) {
                    setInputMethodWithSubtypeIdLocked(token, id, InputMethodUtils.getSubtypeIdFromHashCode(this.mMethodMap.get(id), subtype.hashCode()));
                } else {
                    setInputMethod(token, id);
                }
            }
        }
    }

    public void showInputMethodAndSubtypeEnablerFromClient(IInputMethodClient client, String inputMethodId) {
        synchronized (this.mMethodMap) {
            if (calledFromValidUserLocked()) {
                executeOrSendMessage(this.mCurMethod, this.mCaller.obtainMessageO(2, inputMethodId));
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean switchToPreviousInputMethod(IBinder token) {
        InputMethodInfo lastImi;
        List<InputMethodInfo> enabled;
        String locale;
        InputMethodSubtype keyboardSubtype;
        synchronized (this.mMethodMap) {
            try {
            } catch (Throwable th) {
                th = th;
            }
            try {
                if (calledWithValidTokenLocked(token)) {
                    Pair<String, String> lastIme = this.mSettings.getLastInputMethodAndSubtypeLocked();
                    if (lastIme != null) {
                        lastImi = this.mMethodMap.get(lastIme.first);
                    } else {
                        lastImi = null;
                    }
                    String targetLastImiId = null;
                    int subtypeId = -1;
                    if (lastIme != null && lastImi != null) {
                        boolean imiIdIsSame = lastImi.getId().equals(this.mCurMethodId);
                        int lastSubtypeHash = Integer.parseInt((String) lastIme.second);
                        int currentSubtypeHash = this.mCurrentSubtype == null ? -1 : this.mCurrentSubtype.hashCode();
                        if (!imiIdIsSame || lastSubtypeHash != currentSubtypeHash) {
                            targetLastImiId = (String) lastIme.first;
                            subtypeId = InputMethodUtils.getSubtypeIdFromHashCode(lastImi, lastSubtypeHash);
                        }
                    }
                    boolean imiIdIsSame2 = TextUtils.isEmpty(targetLastImiId);
                    if (imiIdIsSame2 && !InputMethodUtils.canAddToLastInputMethod(this.mCurrentSubtype) && (enabled = this.mSettings.getEnabledInputMethodListLocked()) != null) {
                        int N = enabled.size();
                        if (this.mCurrentSubtype == null) {
                            locale = this.mRes.getConfiguration().locale.toString();
                        } else {
                            locale = this.mCurrentSubtype.getLocale();
                        }
                        for (int i = 0; i < N; i++) {
                            InputMethodInfo imi = enabled.get(i);
                            if (imi.getSubtypeCount() > 0 && imi.isSystem() && (keyboardSubtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(this.mRes, InputMethodUtils.getSubtypes(imi), "keyboard", locale, true)) != null) {
                                targetLastImiId = imi.getId();
                                subtypeId = InputMethodUtils.getSubtypeIdFromHashCode(imi, keyboardSubtype.hashCode());
                                if (keyboardSubtype.getLocale().equals(locale)) {
                                    break;
                                }
                            }
                        }
                    }
                    if (TextUtils.isEmpty(targetLastImiId)) {
                        return false;
                    }
                    Slog.d(TAG, "Switch to: " + lastImi.getId() + ", " + ((String) lastIme.second) + ", from: " + this.mCurMethodId + ", " + subtypeId);
                    setInputMethodWithSubtypeIdLocked(token, targetLastImiId, subtypeId);
                    return true;
                }
                return false;
            } catch (Throwable th2) {
                th = th2;
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean switchToNextInputMethod(IBinder token, boolean onlyCurrentIme) {
        synchronized (this.mMethodMap) {
            if (calledWithValidTokenLocked(token)) {
                InputMethodSubtypeSwitchingController.ImeSubtypeListItem nextSubtype = this.mSwitchingController.getNextInputMethodLocked(onlyCurrentIme, this.mMethodMap.get(this.mCurMethodId), this.mCurrentSubtype);
                if (nextSubtype == null) {
                    return false;
                }
                setInputMethodWithSubtypeIdLocked(token, nextSubtype.mImi.getId(), nextSubtype.mSubtypeId);
                return true;
            }
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean shouldOfferSwitchingToNextInputMethod(IBinder token) {
        synchronized (this.mMethodMap) {
            if (calledWithValidTokenLocked(token)) {
                InputMethodSubtypeSwitchingController.ImeSubtypeListItem nextSubtype = this.mSwitchingController.getNextInputMethodLocked(false, this.mMethodMap.get(this.mCurMethodId), this.mCurrentSubtype);
                return nextSubtype != null;
            }
            return false;
        }
    }

    public InputMethodSubtype getLastInputMethodSubtype() {
        synchronized (this.mMethodMap) {
            if (calledFromValidUserLocked()) {
                Pair<String, String> lastIme = this.mSettings.getLastInputMethodAndSubtypeLocked();
                if (lastIme != null && !TextUtils.isEmpty((CharSequence) lastIme.first) && !TextUtils.isEmpty((CharSequence) lastIme.second)) {
                    InputMethodInfo lastImi = this.mMethodMap.get(lastIme.first);
                    if (lastImi == null) {
                        return null;
                    }
                    try {
                        int lastSubtypeHash = Integer.parseInt((String) lastIme.second);
                        int lastSubtypeId = InputMethodUtils.getSubtypeIdFromHashCode(lastImi, lastSubtypeHash);
                        if (lastSubtypeId >= 0 && lastSubtypeId < lastImi.getSubtypeCount()) {
                            return lastImi.getSubtypeAt(lastSubtypeId);
                        }
                        return null;
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                return null;
            }
            return null;
        }
    }

    public void setAdditionalInputMethodSubtypes(String imiId, InputMethodSubtype[] subtypes) {
        if (TextUtils.isEmpty(imiId) || subtypes == null) {
            return;
        }
        ArrayList<InputMethodSubtype> toBeAdded = new ArrayList<>();
        for (InputMethodSubtype subtype : subtypes) {
            if (!toBeAdded.contains(subtype)) {
                toBeAdded.add(subtype);
            } else {
                Slog.w(TAG, "Duplicated subtype definition found: " + subtype.getLocale() + ", " + subtype.getMode());
            }
        }
        synchronized (this.mMethodMap) {
            if (calledFromValidUserLocked()) {
                if (this.mSystemReady) {
                    InputMethodInfo imi = this.mMethodMap.get(imiId);
                    if (imi == null) {
                        return;
                    }
                    try {
                        String[] packageInfos = this.mIPackageManager.getPackagesForUid(Binder.getCallingUid());
                        if (packageInfos != null) {
                            for (String str : packageInfos) {
                                if (str.equals(imi.getPackageName())) {
                                    if (subtypes.length > 0) {
                                        this.mAdditionalSubtypeMap.put(imi.getId(), toBeAdded);
                                    } else {
                                        this.mAdditionalSubtypeMap.remove(imi.getId());
                                    }
                                    AdditionalSubtypeUtils.save(this.mAdditionalSubtypeMap, this.mMethodMap, this.mSettings.getCurrentUserId());
                                    long ident = Binder.clearCallingIdentity();
                                    buildInputMethodListLocked(false);
                                    Binder.restoreCallingIdentity(ident);
                                    return;
                                }
                            }
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Failed to get package infos");
                    }
                }
            }
        }
    }

    public int getInputMethodWindowVisibleHeight() {
        return this.mWindowManagerInternal.getInputMethodWindowVisibleHeight(this.mCurTokenDisplayId);
    }

    /* JADX WARN: Code restructure failed: missing block: B:33:0x0093, code lost:
        if (r12.mWindowManagerInternal.isUidAllowedOnDisplay(r14, r3.uid) == false) goto L36;
     */
    /* JADX WARN: Code restructure failed: missing block: B:34:0x0095, code lost:
        r4 = new com.android.server.inputmethod.InputMethodManagerService.ActivityViewInfo(r3, new android.graphics.Matrix());
        r12.mActivityViewDisplayIdToParentMap.put(r14, r4);
     */
    /* JADX WARN: Code restructure failed: missing block: B:36:0x00bf, code lost:
        throw new java.lang.SecurityException(r3 + " cannot access to display #" + r14);
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void reportActivityView(com.android.internal.view.IInputMethodClient r13, int r14, float[] r15) {
        /*
            Method dump skipped, instructions count: 329
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.inputmethod.InputMethodManagerService.reportActivityView(com.android.internal.view.IInputMethodClient, int, float[]):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyUserAction(IBinder token) {
        Slog.d(TAG, "Got the notification of a user action.");
        synchronized (this.mMethodMap) {
            if (this.mCurToken != token) {
                Slog.d(TAG, "Ignoring the user action notification from IMEs that are no longer active.");
                return;
            }
            InputMethodInfo imi = this.mMethodMap.get(this.mCurMethodId);
            if (imi != null) {
                this.mSwitchingController.onUserActionLocked(imi, this.mCurrentSubtype);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void reportPreRendered(IBinder token, EditorInfo info) {
        synchronized (this.mMethodMap) {
            if (calledWithValidTokenLocked(token)) {
                if (this.mCurClient != null && this.mCurClient.client != null) {
                    executeOrSendMessage(this.mCurClient.client, this.mCaller.obtainMessageOO(3060, info, this.mCurClient));
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void applyImeVisibility(IBinder token, boolean setVisible) {
        synchronized (this.mMethodMap) {
            if (calledWithValidTokenLocked(token)) {
                if (this.mCurClient != null && this.mCurClient.client != null) {
                    executeOrSendMessage(this.mCurClient.client, this.mCaller.obtainMessageIO(3070, setVisible ? 1 : 0, this.mCurClient));
                }
            }
        }
    }

    private void setInputMethodWithSubtypeIdLocked(IBinder token, String id, int subtypeId) {
        if (token == null) {
            if (this.mContext.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != 0) {
                throw new SecurityException("Using null token requires permission android.permission.WRITE_SECURE_SETTINGS");
            }
        } else if (this.mCurToken != token) {
            Slog.w(TAG, "Ignoring setInputMethod of uid " + Binder.getCallingUid() + " token: " + token);
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            setInputMethodLocked(id, subtypeId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void hideMySoftInput(IBinder token, int flags) {
        synchronized (this.mMethodMap) {
            if (calledWithValidTokenLocked(token)) {
                long ident = Binder.clearCallingIdentity();
                hideCurrentInputLocked(flags, null);
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showMySoftInput(IBinder token, int flags) {
        synchronized (this.mMethodMap) {
            if (calledWithValidTokenLocked(token)) {
                long ident = Binder.clearCallingIdentity();
                showCurrentInputLocked(flags, null);
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    void setEnabledSessionInMainThread(SessionState session) {
        SessionState sessionState = this.mEnabledSession;
        if (sessionState != session) {
            if (sessionState != null && sessionState.session != null) {
                try {
                    Slog.v(TAG, "Disabling: " + this.mEnabledSession);
                    this.mEnabledSession.method.setSessionEnabled(this.mEnabledSession.session, false);
                } catch (RemoteException e) {
                }
            }
            this.mEnabledSession = session;
            SessionState sessionState2 = this.mEnabledSession;
            if (sessionState2 != null && sessionState2.session != null) {
                try {
                    Slog.v(TAG, "Enabling: " + this.mEnabledSession);
                    this.mEnabledSession.method.setSessionEnabled(this.mEnabledSession.session, true);
                } catch (RemoteException e2) {
                }
            }
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:103:0x01f6, code lost:
        if (android.os.Binder.isProxy(r1) != false) goto L94;
     */
    /* JADX WARN: Code restructure failed: missing block: B:114:0x020d, code lost:
        if (android.os.Binder.isProxy(r1) != false) goto L94;
     */
    /* JADX WARN: Code restructure failed: missing block: B:115:0x020f, code lost:
        r2.dispose();
     */
    /* JADX WARN: Code restructure failed: missing block: B:75:0x0182, code lost:
        if (android.os.Binder.isProxy(r1) != false) goto L59;
     */
    @Override // android.os.Handler.Callback
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public boolean handleMessage(android.os.Message r15) {
        /*
            Method dump skipped, instructions count: 902
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.inputmethod.InputMethodManagerService.handleMessage(android.os.Message):boolean");
    }

    private void handleSetInteractive(boolean interactive) {
        synchronized (this.mMethodMap) {
            this.mIsInteractive = interactive;
            updateSystemUiLocked(interactive ? this.mImeWindowVis : 0, this.mBackDisposition);
            if (this.mCurClient != null && this.mCurClient.client != null) {
                executeOrSendMessage(this.mCurClient.client, this.mCaller.obtainMessageIIO((int) MSG_SET_ACTIVE, this.mIsInteractive ? 1 : 0, this.mInFullscreenMode ? 1 : 0, this.mCurClient));
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean chooseNewDefaultIMELocked() {
        InputMethodInfo imi = InputMethodUtils.getMostApplicableDefaultIME(this.mSettings.getEnabledInputMethodListLocked());
        if (imi != null) {
            Slog.d(TAG, "New default IME was selected: " + imi.getId());
            resetSelectedInputMethodAndSubtypeLocked(imi.getId());
            return true;
        }
        return false;
    }

    static void queryInputMethodServicesInternal(Context context, int userId, ArrayMap<String, List<InputMethodSubtype>> additionalSubtypeMap, ArrayMap<String, InputMethodInfo> methodMap, ArrayList<InputMethodInfo> methodList) {
        methodList.clear();
        methodMap.clear();
        List<ResolveInfo> services = context.getPackageManager().queryIntentServicesAsUser(new Intent("android.view.InputMethod"), 32896, userId);
        methodList.ensureCapacity(services.size());
        methodMap.ensureCapacity(services.size());
        for (int i = 0; i < services.size(); i++) {
            ResolveInfo ri = services.get(i);
            ServiceInfo si = ri.serviceInfo;
            String imeId = InputMethodInfo.computeId(ri);
            if ("android.permission.BIND_INPUT_METHOD".equals(si.permission)) {
                Slog.d(TAG, "Checking " + imeId);
                try {
                    InputMethodInfo imi = new InputMethodInfo(context, ri, additionalSubtypeMap.get(imeId));
                    if (!imi.isVrOnly()) {
                        methodList.add(imi);
                        methodMap.put(imi.getId(), imi);
                        Slog.d(TAG, "Found an input method " + imi);
                    }
                } catch (Exception e) {
                    Slog.wtf(TAG, "Unable to load input method " + imeId, e);
                }
            } else {
                Slog.w(TAG, "Skipping input method " + imeId + ": it does not require the permission android.permission.BIND_INPUT_METHOD");
            }
        }
    }

    @GuardedBy({"mMethodMap"})
    void buildInputMethodListLocked(boolean resetDefaultEnabledIme) {
        Slog.d(TAG, "--- re-buildInputMethodList reset = " + resetDefaultEnabledIme + " \n ------ caller=" + Debug.getCallers(10));
        if (!this.mSystemReady) {
            Slog.e(TAG, "buildInputMethodListLocked is not allowed until system is ready");
            return;
        }
        this.mMethodMapUpdateCount++;
        this.mMyPackageMonitor.clearKnownImePackageNamesLocked();
        queryInputMethodServicesInternal(this.mContext, this.mSettings.getCurrentUserId(), this.mAdditionalSubtypeMap, this.mMethodMap, this.mMethodList);
        List<ResolveInfo> allInputMethodServices = this.mContext.getPackageManager().queryIntentServicesAsUser(new Intent("android.view.InputMethod"), 512, this.mSettings.getCurrentUserId());
        int N = allInputMethodServices.size();
        for (int i = 0; i < N; i++) {
            ServiceInfo si = allInputMethodServices.get(i).serviceInfo;
            if ("android.permission.BIND_INPUT_METHOD".equals(si.permission)) {
                this.mMyPackageMonitor.addKnownImePackageNameLocked(si.packageName);
            }
        }
        boolean reenableMinimumNonAuxSystemImes = false;
        if (!resetDefaultEnabledIme) {
            boolean enabledImeFound = false;
            boolean enabledNonAuxImeFound = false;
            List<InputMethodInfo> enabledImes = this.mSettings.getEnabledInputMethodListLocked();
            int N2 = enabledImes.size();
            int i2 = 0;
            while (true) {
                if (i2 >= N2) {
                    break;
                }
                InputMethodInfo imi = enabledImes.get(i2);
                if (this.mMethodList.contains(imi)) {
                    enabledImeFound = true;
                    if (!imi.isAuxiliaryIme()) {
                        enabledNonAuxImeFound = true;
                        break;
                    }
                }
                i2++;
            }
            if (!enabledImeFound) {
                Slog.i(TAG, "All the enabled IMEs are gone. Reset default enabled IMEs.");
                resetDefaultEnabledIme = true;
                resetSelectedInputMethodAndSubtypeLocked("");
            } else if (!enabledNonAuxImeFound) {
                Slog.i(TAG, "All the enabled non-Aux IMEs are gone. Do partial reset.");
                reenableMinimumNonAuxSystemImes = true;
            }
        }
        if (resetDefaultEnabledIme || reenableMinimumNonAuxSystemImes) {
            ArrayList<InputMethodInfo> defaultEnabledIme = InputMethodUtils.getDefaultEnabledImes(this.mContext, this.mMethodList, reenableMinimumNonAuxSystemImes);
            int N3 = defaultEnabledIme.size();
            for (int i3 = 0; i3 < N3; i3++) {
                InputMethodInfo imi2 = defaultEnabledIme.get(i3);
                Slog.d(TAG, "--- enable ime = " + imi2);
                setInputMethodEnabledLocked(imi2.getId(), true);
            }
        }
        String defaultImiId = this.mSettings.getSelectedInputMethod();
        if (!TextUtils.isEmpty(defaultImiId)) {
            if (!this.mMethodMap.containsKey(defaultImiId)) {
                Slog.w(TAG, "Default IME is uninstalled. Choose new default IME.");
                if (chooseNewDefaultIMELocked()) {
                    updateInputMethodsFromSettingsLocked(true);
                }
            } else {
                setInputMethodEnabledLocked(defaultImiId, true);
            }
        }
        this.mSwitchingController.resetCircularListLocked(this.mContext);
    }

    private void showInputMethodAndSubtypeEnabler(String inputMethodId) {
        int userId;
        Intent intent = new Intent("android.settings.INPUT_METHOD_SUBTYPE_SETTINGS");
        intent.setFlags(337641472);
        if (!TextUtils.isEmpty(inputMethodId)) {
            intent.putExtra("input_method_id", inputMethodId);
        }
        synchronized (this.mMethodMap) {
            userId = this.mSettings.getCurrentUserId();
        }
        this.mContext.startActivityAsUser(intent, null, UserHandle.of(userId));
    }

    private void showConfigureInputMethods() {
        Intent intent = new Intent("android.settings.INPUT_METHOD_SETTINGS");
        intent.setFlags(337641472);
        this.mContext.startActivityAsUser(intent, null, UserHandle.CURRENT);
    }

    private boolean isScreenLocked() {
        KeyguardManager keyguardManager = this.mKeyguardManager;
        return keyguardManager != null && keyguardManager.isKeyguardLocked() && this.mKeyguardManager.isKeyguardSecure();
    }

    private void showInputMethodMenu(boolean showAuxSubtypes, int displayId) {
        int subtypeId;
        InputMethodSubtype currentSubtype;
        Slog.v(TAG, "Show switching menu. showAuxSubtypes=" + showAuxSubtypes);
        boolean isScreenLocked = isScreenLocked();
        String lastInputMethodId = this.mSettings.getSelectedInputMethod();
        int lastInputMethodSubtypeId = this.mSettings.getSelectedInputMethodSubtypeId(lastInputMethodId);
        Slog.v(TAG, "Current IME: " + lastInputMethodId);
        synchronized (this.mMethodMap) {
            List<InputMethodSubtypeSwitchingController.ImeSubtypeListItem> imList = this.mSwitchingController.getSortedInputMethodAndSubtypeListLocked(showAuxSubtypes, isScreenLocked);
            if (imList.isEmpty()) {
                return;
            }
            hideInputMethodMenuLocked();
            if (lastInputMethodSubtypeId == -1 && (currentSubtype = getCurrentInputMethodSubtypeLocked()) != null) {
                InputMethodInfo currentImi = this.mMethodMap.get(this.mCurMethodId);
                lastInputMethodSubtypeId = InputMethodUtils.getSubtypeIdFromHashCode(currentImi, currentSubtype.hashCode());
            }
            int N = imList.size();
            this.mIms = new InputMethodInfo[N];
            this.mSubtypeIds = new int[N];
            int checkedItem = 0;
            for (int i = 0; i < N; i++) {
                InputMethodSubtypeSwitchingController.ImeSubtypeListItem item = imList.get(i);
                this.mIms[i] = item.mImi;
                this.mSubtypeIds[i] = item.mSubtypeId;
                if (this.mIms[i].getId().equals(lastInputMethodId) && ((subtypeId = this.mSubtypeIds[i]) == -1 || ((lastInputMethodSubtypeId == -1 && subtypeId == 0) || subtypeId == lastInputMethodSubtypeId))) {
                    checkedItem = i;
                }
            }
            ActivityThread currentThread = ActivityThread.currentActivityThread();
            Context settingsContext = new ContextThemeWrapper((Context) (displayId == 0 ? currentThread.getSystemUiContext() : currentThread.createSystemUiContext(displayId)), 16974371);
            this.mDialogBuilder = new AlertDialog.Builder(settingsContext);
            this.mDialogBuilder.setOnCancelListener(new DialogInterface.OnCancelListener() { // from class: com.android.server.inputmethod.InputMethodManagerService.3
                @Override // android.content.DialogInterface.OnCancelListener
                public void onCancel(DialogInterface dialog) {
                    InputMethodManagerService.this.hideInputMethodMenu();
                }
            });
            Context dialogContext = this.mDialogBuilder.getContext();
            TypedArray a = dialogContext.obtainStyledAttributes(null, R.styleable.DialogPreference, 16842845, 0);
            Drawable dialogIcon = a.getDrawable(2);
            a.recycle();
            this.mDialogBuilder.setIcon(dialogIcon);
            LayoutInflater inflater = (LayoutInflater) dialogContext.getSystemService(LayoutInflater.class);
            View tv = inflater.inflate(17367171, (ViewGroup) null);
            this.mDialogBuilder.setCustomTitle(tv);
            this.mSwitchingDialogTitleView = tv;
            this.mSwitchingDialogTitleView.findViewById(16909071).setVisibility(this.mWindowManagerInternal.isHardKeyboardAvailable() ? 0 : 8);
            Switch hardKeySwitch = (Switch) this.mSwitchingDialogTitleView.findViewById(16909072);
            hardKeySwitch.setChecked(this.mShowImeWithHardKeyboard);
            hardKeySwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.android.server.inputmethod.InputMethodManagerService.4
                @Override // android.widget.CompoundButton.OnCheckedChangeListener
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    InputMethodManagerService.this.mSettings.setShowImeWithHardKeyboard(isChecked);
                    InputMethodManagerService.this.hideInputMethodMenu();
                }
            });
            final ImeSubtypeListAdapter adapter = new ImeSubtypeListAdapter(dialogContext, 17367172, imList, checkedItem);
            DialogInterface.OnClickListener choiceListener = new DialogInterface.OnClickListener() { // from class: com.android.server.inputmethod.InputMethodManagerService.5
                @Override // android.content.DialogInterface.OnClickListener
                public void onClick(DialogInterface dialog, int which) {
                    synchronized (InputMethodManagerService.this.mMethodMap) {
                        if (InputMethodManagerService.this.mIms != null && InputMethodManagerService.this.mIms.length > which && InputMethodManagerService.this.mSubtypeIds != null && InputMethodManagerService.this.mSubtypeIds.length > which) {
                            InputMethodInfo im = InputMethodManagerService.this.mIms[which];
                            int subtypeId2 = InputMethodManagerService.this.mSubtypeIds[which];
                            adapter.mCheckedItem = which;
                            adapter.notifyDataSetChanged();
                            InputMethodManagerService.this.hideInputMethodMenu();
                            if (im != null) {
                                subtypeId2 = (subtypeId2 < 0 || subtypeId2 >= im.getSubtypeCount()) ? -1 : -1;
                                InputMethodManagerService.this.setInputMethodLocked(im.getId(), subtypeId2);
                            }
                        }
                    }
                }
            };
            this.mDialogBuilder.setSingleChoiceItems(adapter, checkedItem, choiceListener);
            this.mSwitchingDialog = this.mDialogBuilder.create();
            this.mSwitchingDialog.setCanceledOnTouchOutside(true);
            Window w = this.mSwitchingDialog.getWindow();
            WindowManager.LayoutParams attrs = w.getAttributes();
            w.setType(2012);
            attrs.token = this.mSwitchingDialogToken;
            attrs.privateFlags |= 16;
            attrs.setTitle("Select input method");
            w.setAttributes(attrs);
            updateSystemUiLocked(this.mImeWindowVis, this.mBackDisposition);
            this.mSwitchingDialog.show();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class ImeSubtypeListAdapter extends ArrayAdapter<InputMethodSubtypeSwitchingController.ImeSubtypeListItem> {
        public int mCheckedItem;
        private final LayoutInflater mInflater;
        private final List<InputMethodSubtypeSwitchingController.ImeSubtypeListItem> mItemsList;
        private final int mTextViewResourceId;

        public ImeSubtypeListAdapter(Context context, int textViewResourceId, List<InputMethodSubtypeSwitchingController.ImeSubtypeListItem> itemsList, int checkedItem) {
            super(context, textViewResourceId, itemsList);
            this.mTextViewResourceId = textViewResourceId;
            this.mItemsList = itemsList;
            this.mCheckedItem = checkedItem;
            this.mInflater = (LayoutInflater) context.getSystemService(LayoutInflater.class);
        }

        @Override // android.widget.ArrayAdapter, android.widget.Adapter
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView : this.mInflater.inflate(this.mTextViewResourceId, (ViewGroup) null);
            if (position < 0 || position >= this.mItemsList.size()) {
                return view;
            }
            InputMethodSubtypeSwitchingController.ImeSubtypeListItem item = this.mItemsList.get(position);
            CharSequence imeName = item.mImeName;
            CharSequence subtypeName = item.mSubtypeName;
            TextView firstTextView = (TextView) view.findViewById(16908308);
            TextView secondTextView = (TextView) view.findViewById(16908309);
            if (TextUtils.isEmpty(subtypeName)) {
                firstTextView.setText(imeName);
                secondTextView.setVisibility(8);
            } else {
                firstTextView.setText(subtypeName);
                secondTextView.setText(imeName);
                secondTextView.setVisibility(0);
            }
            RadioButton radioButton = (RadioButton) view.findViewById(16909370);
            radioButton.setChecked(position == this.mCheckedItem);
            return view;
        }
    }

    void hideInputMethodMenu() {
        synchronized (this.mMethodMap) {
            hideInputMethodMenuLocked();
        }
    }

    void hideInputMethodMenuLocked() {
        Slog.v(TAG, "Hide switching menu");
        AlertDialog alertDialog = this.mSwitchingDialog;
        if (alertDialog != null) {
            alertDialog.dismiss();
            this.mSwitchingDialog = null;
            this.mSwitchingDialogTitleView = null;
        }
        updateSystemUiLocked(this.mImeWindowVis, this.mBackDisposition);
        this.mDialogBuilder = null;
        this.mIms = null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean setInputMethodEnabledLocked(String id, boolean enabled) {
        List<Pair<String, ArrayList<String>>> enabledInputMethodsList = this.mSettings.getEnabledInputMethodsAndSubtypeListLocked();
        if (enabled) {
            for (Pair<String, ArrayList<String>> pair : enabledInputMethodsList) {
                if (((String) pair.first).equals(id)) {
                    return true;
                }
            }
            this.mSettings.appendAndPutEnabledInputMethodLocked(id, false);
            return false;
        }
        StringBuilder builder = new StringBuilder();
        if (this.mSettings.buildAndPutEnabledInputMethodsStrRemovingIdLocked(builder, enabledInputMethodsList, id)) {
            String selId = this.mSettings.getSelectedInputMethod();
            if (id.equals(selId) && !chooseNewDefaultIMELocked()) {
                Slog.i(TAG, "Can't find new IME, unsetting the current input method.");
                resetSelectedInputMethodAndSubtypeLocked("");
            }
            return true;
        }
        return false;
    }

    private void setSelectedInputMethodAndSubtypeLocked(InputMethodInfo imi, int subtypeId, boolean setSubtypeOnly) {
        this.mSettings.saveCurrentInputMethodAndSubtypeToHistory(this.mCurMethodId, this.mCurrentSubtype);
        if (imi == null || subtypeId < 0) {
            this.mSettings.putSelectedSubtype(-1);
            this.mCurrentSubtype = null;
        } else if (subtypeId >= imi.getSubtypeCount()) {
            this.mSettings.putSelectedSubtype(-1);
            this.mCurrentSubtype = getCurrentInputMethodSubtypeLocked();
        } else {
            InputMethodSubtype subtype = imi.getSubtypeAt(subtypeId);
            this.mSettings.putSelectedSubtype(subtype.hashCode());
            this.mCurrentSubtype = subtype;
        }
        if (!setSubtypeOnly) {
            this.mSettings.putSelectedInputMethod(imi != null ? imi.getId() : "");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void resetSelectedInputMethodAndSubtypeLocked(String newDefaultIme) {
        String subtypeHashCode;
        InputMethodInfo imi = this.mMethodMap.get(newDefaultIme);
        int lastSubtypeId = -1;
        if (imi != null && !TextUtils.isEmpty(newDefaultIme) && (subtypeHashCode = this.mSettings.getLastSubtypeForInputMethodLocked(newDefaultIme)) != null) {
            try {
                lastSubtypeId = InputMethodUtils.getSubtypeIdFromHashCode(imi, Integer.parseInt(subtypeHashCode));
            } catch (NumberFormatException e) {
                Slog.w(TAG, "HashCode for subtype looks broken: " + subtypeHashCode, e);
            }
        }
        setSelectedInputMethodAndSubtypeLocked(imi, lastSubtypeId, false);
    }

    public InputMethodSubtype getCurrentInputMethodSubtype() {
        synchronized (this.mMethodMap) {
            if (!calledFromValidUserLocked()) {
                return null;
            }
            return getCurrentInputMethodSubtypeLocked();
        }
    }

    private InputMethodSubtype getCurrentInputMethodSubtypeLocked() {
        InputMethodSubtype inputMethodSubtype;
        if (this.mCurMethodId == null) {
            return null;
        }
        boolean subtypeIsSelected = this.mSettings.isSubtypeSelected();
        InputMethodInfo imi = this.mMethodMap.get(this.mCurMethodId);
        if (imi == null || imi.getSubtypeCount() == 0) {
            return null;
        }
        if (!subtypeIsSelected || (inputMethodSubtype = this.mCurrentSubtype) == null || !InputMethodUtils.isValidSubtypeId(imi, inputMethodSubtype.hashCode())) {
            int subtypeId = this.mSettings.getSelectedInputMethodSubtypeId(this.mCurMethodId);
            if (subtypeId == -1) {
                List<InputMethodSubtype> explicitlyOrImplicitlyEnabledSubtypes = this.mSettings.getEnabledInputMethodSubtypeListLocked(this.mContext, imi, true);
                if (explicitlyOrImplicitlyEnabledSubtypes.size() != 1) {
                    if (explicitlyOrImplicitlyEnabledSubtypes.size() > 1) {
                        this.mCurrentSubtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(this.mRes, explicitlyOrImplicitlyEnabledSubtypes, "keyboard", null, true);
                        if (this.mCurrentSubtype == null) {
                            this.mCurrentSubtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(this.mRes, explicitlyOrImplicitlyEnabledSubtypes, null, null, true);
                        }
                    }
                } else {
                    this.mCurrentSubtype = explicitlyOrImplicitlyEnabledSubtypes.get(0);
                }
            } else {
                this.mCurrentSubtype = InputMethodUtils.getSubtypes(imi).get(subtypeId);
            }
        }
        return this.mCurrentSubtype;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public List<InputMethodInfo> getInputMethodListAsUser(int userId) {
        List<InputMethodInfo> inputMethodListLocked;
        synchronized (this.mMethodMap) {
            inputMethodListLocked = getInputMethodListLocked(userId);
        }
        return inputMethodListLocked;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public List<InputMethodInfo> getEnabledInputMethodListAsUser(int userId) {
        List<InputMethodInfo> enabledInputMethodListLocked;
        synchronized (this.mMethodMap) {
            enabledInputMethodListLocked = getEnabledInputMethodListLocked(userId);
        }
        return enabledInputMethodListLocked;
    }

    /* loaded from: classes.dex */
    private static final class LocalServiceImpl extends InputMethodManagerInternal {
        private final InputMethodManagerService mService;

        LocalServiceImpl(InputMethodManagerService service) {
            this.mService = service;
        }

        @Override // com.android.server.inputmethod.InputMethodManagerInternal
        public void setInteractive(boolean interactive) {
            this.mService.mHandler.obtainMessage(InputMethodManagerService.MSG_SET_INTERACTIVE, interactive ? 1 : 0, 0).sendToTarget();
        }

        @Override // com.android.server.inputmethod.InputMethodManagerInternal
        public void hideCurrentInputMethod() {
            this.mService.mHandler.removeMessages(InputMethodManagerService.MSG_HIDE_CURRENT_INPUT_METHOD);
            this.mService.mHandler.sendEmptyMessage(InputMethodManagerService.MSG_HIDE_CURRENT_INPUT_METHOD);
        }

        @Override // com.android.server.inputmethod.InputMethodManagerInternal
        public List<InputMethodInfo> getInputMethodListAsUser(int userId) {
            return this.mService.getInputMethodListAsUser(userId);
        }

        @Override // com.android.server.inputmethod.InputMethodManagerInternal
        public List<InputMethodInfo> getEnabledInputMethodListAsUser(int userId) {
            return this.mService.getEnabledInputMethodListAsUser(userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public IInputContentUriToken createInputContentUriToken(IBinder token, Uri contentUri, String packageName) {
        if (token == null) {
            throw new NullPointerException("token");
        }
        if (packageName == null) {
            throw new NullPointerException("packageName");
        }
        if (contentUri == null) {
            throw new NullPointerException("contentUri");
        }
        String contentUriScheme = contentUri.getScheme();
        if (!ActivityTaskManagerInternal.ASSIST_KEY_CONTENT.equals(contentUriScheme)) {
            throw new InvalidParameterException("contentUri must have content scheme");
        }
        synchronized (this.mMethodMap) {
            int uid = Binder.getCallingUid();
            if (this.mCurMethodId == null) {
                return null;
            }
            if (this.mCurToken != token) {
                Slog.e(TAG, "Ignoring createInputContentUriToken mCurToken=" + this.mCurToken + " token=" + token);
                return null;
            } else if (!TextUtils.equals(this.mCurAttribute.packageName, packageName)) {
                Slog.e(TAG, "Ignoring createInputContentUriToken mCurAttribute.packageName=" + this.mCurAttribute.packageName + " packageName=" + packageName);
                return null;
            } else {
                int imeUserId = UserHandle.getUserId(uid);
                int appUserId = UserHandle.getUserId(this.mCurClient.uid);
                int contentUriOwnerUserId = ContentProvider.getUserIdFromUri(contentUri, imeUserId);
                Uri contentUriWithoutUserId = ContentProvider.getUriWithoutUserId(contentUri);
                return new InputContentUriTokenHandler(contentUriWithoutUserId, uid, packageName, contentUriOwnerUserId, appUserId);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void reportFullscreenMode(IBinder token, boolean fullscreen) {
        synchronized (this.mMethodMap) {
            if (calledWithValidTokenLocked(token)) {
                if (this.mCurClient != null && this.mCurClient.client != null) {
                    this.mInFullscreenMode = fullscreen;
                    executeOrSendMessage(this.mCurClient.client, this.mCaller.obtainMessageIO((int) MSG_REPORT_FULLSCREEN_MODE, fullscreen ? 1 : 0, this.mCurClient));
                }
            }
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        ClientState client;
        ClientState focusedWindowClient;
        IInputMethod method;
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            Printer p = new PrintWriterPrinter(pw);
            synchronized (this.mMethodMap) {
                p.println("Current Input Method Manager state:");
                int N = this.mMethodList.size();
                p.println("  Input Methods: mMethodMapUpdateCount=" + this.mMethodMapUpdateCount);
                for (int i = 0; i < N; i++) {
                    InputMethodInfo info = this.mMethodList.get(i);
                    p.println("  InputMethod #" + i + ":");
                    info.dump(p, "    ");
                }
                p.println("  Clients:");
                int numClients = this.mClients.size();
                for (int i2 = 0; i2 < numClients; i2++) {
                    ClientState ci = this.mClients.valueAt(i2);
                    p.println("  Client " + ci + ":");
                    StringBuilder sb = new StringBuilder();
                    sb.append("    client=");
                    sb.append(ci.client);
                    p.println(sb.toString());
                    p.println("    inputContext=" + ci.inputContext);
                    p.println("    sessionRequested=" + ci.sessionRequested);
                    p.println("    curSession=" + ci.curSession);
                }
                p.println("  mCurMethodId=" + this.mCurMethodId);
                client = this.mCurClient;
                p.println("  mCurClient=" + client + " mCurSeq=" + this.mCurSeq);
                p.println("  mCurFocusedWindow=" + this.mCurFocusedWindow + " softInputMode=" + InputMethodDebug.softInputModeToString(this.mCurFocusedWindowSoftInputMode) + " client=" + this.mCurFocusedWindowClient);
                focusedWindowClient = this.mCurFocusedWindowClient;
                p.println("  mCurId=" + this.mCurId + " mHaveConnection=" + this.mHaveConnection + " mBoundToMethod=" + this.mBoundToMethod + " mVisibleBound=" + this.mVisibleBound);
                StringBuilder sb2 = new StringBuilder();
                sb2.append("  mCurToken=");
                sb2.append(this.mCurToken);
                p.println(sb2.toString());
                StringBuilder sb3 = new StringBuilder();
                sb3.append("  mCurTokenDisplayId=");
                sb3.append(this.mCurTokenDisplayId);
                p.println(sb3.toString());
                p.println("  mCurIntent=" + this.mCurIntent);
                method = this.mCurMethod;
                p.println("  mCurMethod=" + this.mCurMethod);
                p.println("  mEnabledSession=" + this.mEnabledSession);
                p.println("  mShowRequested=" + this.mShowRequested + " mShowExplicitlyRequested=" + this.mShowExplicitlyRequested + " mShowForced=" + this.mShowForced + " mInputShown=" + this.mInputShown);
                StringBuilder sb4 = new StringBuilder();
                sb4.append("  mInFullscreenMode=");
                sb4.append(this.mInFullscreenMode);
                p.println(sb4.toString());
                p.println("  mSystemReady=" + this.mSystemReady + " mInteractive=" + this.mIsInteractive);
                StringBuilder sb5 = new StringBuilder();
                sb5.append("  mSettingsObserver=");
                sb5.append(this.mSettingsObserver);
                p.println(sb5.toString());
                p.println("  mSwitchingController:");
                this.mSwitchingController.dump(p);
                p.println("  mSettings:");
                this.mSettings.dumpLocked(p, "    ");
                p.println("  mStartInputHistory:");
                this.mStartInputHistory.dump(pw, "   ");
            }
            p.println(" ");
            if (client != null) {
                pw.flush();
                try {
                    TransferPipe.dumpAsync(client.client.asBinder(), fd, args);
                } catch (RemoteException | IOException e) {
                    p.println("Failed to dump input method client: " + e);
                }
            } else {
                p.println("No input method client.");
            }
            if (focusedWindowClient != null && client != focusedWindowClient) {
                p.println(" ");
                p.println("Warning: Current input method client doesn't match the last focused. window.");
                p.println("Dumping input method client in the last focused window just in case.");
                p.println(" ");
                pw.flush();
                try {
                    TransferPipe.dumpAsync(focusedWindowClient.client.asBinder(), fd, args);
                } catch (RemoteException | IOException e2) {
                    p.println("Failed to dump input method client in focused window: " + e2);
                }
            }
            p.println(" ");
            if (method != null) {
                pw.flush();
                try {
                    TransferPipe.dumpAsync(method.asBinder(), fd, args);
                    return;
                } catch (RemoteException | IOException e3) {
                    p.println("Failed to dump input method service: " + e3);
                    return;
                }
            }
            p.println("No input method service.");
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) throws RemoteException {
        int callingUid = Binder.getCallingUid();
        if (callingUid != 0 && callingUid != MSG_START_INPUT) {
            if (resultReceiver != null) {
                resultReceiver.send(-1, null);
            }
            String errorMsg = "InputMethodManagerService does not support shell commands from non-shell users. callingUid=" + callingUid + " args=" + Arrays.toString(args);
            if (Process.isCoreUid(callingUid)) {
                Slog.e(TAG, errorMsg);
                return;
            }
            throw new SecurityException(errorMsg);
        }
        new ShellCommandImpl(this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class ShellCommandImpl extends ShellCommand {
        final InputMethodManagerService mService;

        ShellCommandImpl(InputMethodManagerService service) {
            this.mService = service;
        }

        public int onCommand(String cmd) {
            Arrays.asList("android.permission.DUMP", "android.permission.INTERACT_ACROSS_USERS_FULL", "android.permission.WRITE_SECURE_SETTINGS").forEach(new Consumer() { // from class: com.android.server.inputmethod.-$$Lambda$InputMethodManagerService$ShellCommandImpl$DbZq_GIUJWcuMsIpw_Jz5jVT2-Y
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    InputMethodManagerService.ShellCommandImpl.this.lambda$onCommand$0$InputMethodManagerService$ShellCommandImpl((String) obj);
                }
            });
            long identity = Binder.clearCallingIdentity();
            try {
                return onCommandWithSystemIdentity(cmd);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        public /* synthetic */ void lambda$onCommand$0$InputMethodManagerService$ShellCommandImpl(String permission) {
            this.mService.mContext.enforceCallingPermission(permission, null);
        }

        /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
        private int onCommandWithSystemIdentity(String cmd) {
            boolean z;
            if ("refresh_debug_properties".equals(cmd)) {
                return refreshDebugProperties();
            }
            if ("get-last-switch-user-id".equals(cmd)) {
                return this.mService.getLastSwitchUserId(this);
            }
            if ("ime".equals(cmd)) {
                String imeCommand = getNextArg();
                if (imeCommand == null || "help".equals(imeCommand) || "-h".equals(imeCommand)) {
                    onImeCommandHelp();
                    return 0;
                }
                switch (imeCommand.hashCode()) {
                    case -1298848381:
                        if (imeCommand.equals(xpInputManagerService.InputPolicyKey.KEY_ENABLE)) {
                            z = true;
                            break;
                        }
                        z = true;
                        break;
                    case 113762:
                        if (imeCommand.equals("set")) {
                            z = true;
                            break;
                        }
                        z = true;
                        break;
                    case 3322014:
                        if (imeCommand.equals("list")) {
                            z = false;
                            break;
                        }
                        z = true;
                        break;
                    case 108404047:
                        if (imeCommand.equals("reset")) {
                            z = true;
                            break;
                        }
                        z = true;
                        break;
                    case 1671308008:
                        if (imeCommand.equals("disable")) {
                            z = true;
                            break;
                        }
                        z = true;
                        break;
                    default:
                        z = true;
                        break;
                }
                if (z) {
                    if (!z) {
                        if (!z) {
                            if (!z) {
                                if (z) {
                                    return this.mService.handleShellCommandResetInputMethod(this);
                                }
                                getOutPrintWriter().println("Unknown command: " + imeCommand);
                                return -1;
                            }
                            return this.mService.handleShellCommandSetInputMethod(this);
                        }
                        return this.mService.handleShellCommandEnableDisableInputMethod(this, false);
                    }
                    return this.mService.handleShellCommandEnableDisableInputMethod(this, true);
                }
                return this.mService.handleShellCommandListInputMethods(this);
            }
            return handleDefaultCommands(cmd);
        }

        private int refreshDebugProperties() {
            DebugFlags.FLAG_OPTIMIZE_START_INPUT.refresh();
            DebugFlags.FLAG_PRE_RENDER_IME_VIEWS.refresh();
            return 0;
        }

        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            try {
                pw.println("InputMethodManagerService commands:");
                pw.println("  help");
                pw.println("    Prints this help text.");
                pw.println("  dump [options]");
                pw.println("    Synonym of dumpsys.");
                pw.println("  ime <command> [options]");
                pw.println("    Manipulate IMEs.  Run \"ime help\" for details.");
                $closeResource(null, pw);
            } catch (Throwable th) {
                try {
                    throw th;
                } catch (Throwable th2) {
                    if (pw != null) {
                        $closeResource(th, pw);
                    }
                    throw th2;
                }
            }
        }

        private static /* synthetic */ void $closeResource(Throwable x0, AutoCloseable x1) {
            if (x0 == null) {
                x1.close();
                return;
            }
            try {
                x1.close();
            } catch (Throwable th) {
                x0.addSuppressed(th);
            }
        }

        private void onImeCommandHelp() {
            IndentingPrintWriter pw = new IndentingPrintWriter(getOutPrintWriter(), "  ", 100);
            try {
                pw.println("ime <command>:");
                pw.increaseIndent();
                pw.println("list [-a] [-s]");
                pw.increaseIndent();
                pw.println("prints all enabled input methods.");
                pw.increaseIndent();
                pw.println("-a: see all input methods");
                pw.println("-s: only a single summary line of each");
                pw.decreaseIndent();
                pw.decreaseIndent();
                pw.println("enable [--user <USER_ID>] <ID>");
                pw.increaseIndent();
                pw.println("allows the given input method ID to be used.");
                pw.increaseIndent();
                pw.print("--user <USER_ID>: Specify which user to enable.");
                pw.println(" Assumes the current user if not specified.");
                pw.decreaseIndent();
                pw.decreaseIndent();
                pw.println("disable [--user <USER_ID>] <ID>");
                pw.increaseIndent();
                pw.println("disallows the given input method ID to be used.");
                pw.increaseIndent();
                pw.print("--user <USER_ID>: Specify which user to disable.");
                pw.println(" Assumes the current user if not specified.");
                pw.decreaseIndent();
                pw.decreaseIndent();
                pw.println("set [--user <USER_ID>] <ID>");
                pw.increaseIndent();
                pw.println("switches to the given input method ID.");
                pw.increaseIndent();
                pw.print("--user <USER_ID>: Specify which user to enable.");
                pw.println(" Assumes the current user if not specified.");
                pw.decreaseIndent();
                pw.decreaseIndent();
                pw.println("reset [--user <USER_ID>]");
                pw.increaseIndent();
                pw.println("reset currently selected/enabled IMEs to the default ones as if the device is initially booted with the current locale.");
                pw.increaseIndent();
                pw.print("--user <USER_ID>: Specify which user to reset.");
                pw.println(" Assumes the current user if not specified.");
                pw.decreaseIndent();
                pw.decreaseIndent();
                pw.decreaseIndent();
                $closeResource(null, pw);
            } finally {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getLastSwitchUserId(ShellCommand shellCommand) {
        synchronized (this.mMethodMap) {
            shellCommand.getOutPrintWriter().println(this.mLastSwitchUserId);
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Code restructure failed: missing block: B:49:0x00c3, code lost:
        if (r0.equals("-a") != false) goto L15;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public int handleShellCommandListInputMethods(android.os.ShellCommand r17) {
        /*
            Method dump skipped, instructions count: 224
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.inputmethod.InputMethodManagerService.handleShellCommandListInputMethods(android.os.ShellCommand):int");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int handleShellCommandEnableDisableInputMethod(ShellCommand shellCommand, boolean enabled) {
        int userIdToBeResolved = handleOptionsForCommandsThatOnlyHaveUserOption(shellCommand);
        String imeId = shellCommand.getNextArgRequired();
        PrintWriter out = shellCommand.getOutPrintWriter();
        PrintWriter error = shellCommand.getErrPrintWriter();
        synchronized (this.mMethodMap) {
            int[] userIds = InputMethodUtils.resolveUserId(userIdToBeResolved, this.mSettings.getCurrentUserId(), shellCommand.getErrPrintWriter());
            for (int userId : userIds) {
                if (userHasDebugPriv(userId, shellCommand)) {
                    handleShellCommandEnableDisableInputMethodInternalLocked(userId, imeId, enabled, out, error);
                }
            }
        }
        return 0;
    }

    private static int handleOptionsForCommandsThatOnlyHaveUserOption(ShellCommand shellCommand) {
        char c;
        do {
            String nextOption = shellCommand.getNextOption();
            if (nextOption != null) {
                c = 65535;
                int hashCode = nextOption.hashCode();
                if (hashCode != 1512) {
                    if (hashCode == 1333469547 && nextOption.equals("--user")) {
                        c = 1;
                    }
                } else if (nextOption.equals("-u")) {
                    c = 0;
                }
                if (c == 0) {
                    break;
                }
            } else {
                return -2;
            }
        } while (c != 1);
        return UserHandle.parseUserArg(shellCommand.getNextArgRequired());
    }

    private void handleShellCommandEnableDisableInputMethodInternalLocked(int userId, String imeId, boolean enabled, PrintWriter out, PrintWriter error) {
        boolean failedToEnableUnknownIme = false;
        boolean previouslyEnabled = false;
        if (userId == this.mSettings.getCurrentUserId()) {
            if (enabled && !this.mMethodMap.containsKey(imeId)) {
                failedToEnableUnknownIme = true;
            } else {
                previouslyEnabled = setInputMethodEnabledLocked(imeId, enabled);
            }
        } else {
            ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
            ArrayList<InputMethodInfo> methodList = new ArrayList<>();
            ArrayMap<String, List<InputMethodSubtype>> additionalSubtypeMap = new ArrayMap<>();
            AdditionalSubtypeUtils.load(additionalSubtypeMap, userId);
            queryInputMethodServicesInternal(this.mContext, userId, additionalSubtypeMap, methodMap, methodList);
            InputMethodUtils.InputMethodSettings settings = new InputMethodUtils.InputMethodSettings(this.mContext.getResources(), this.mContext.getContentResolver(), methodMap, userId, false);
            if (enabled) {
                if (!methodMap.containsKey(imeId)) {
                    failedToEnableUnknownIme = true;
                } else {
                    Iterator<InputMethodInfo> it = settings.getEnabledInputMethodListLocked().iterator();
                    while (true) {
                        if (!it.hasNext()) {
                            break;
                        }
                        InputMethodInfo imi = it.next();
                        if (TextUtils.equals(imi.getId(), imeId)) {
                            previouslyEnabled = true;
                            break;
                        }
                    }
                    if (!previouslyEnabled) {
                        settings.appendAndPutEnabledInputMethodLocked(imeId, false);
                    }
                }
            } else {
                previouslyEnabled = settings.buildAndPutEnabledInputMethodsStrRemovingIdLocked(new StringBuilder(), settings.getEnabledInputMethodsAndSubtypeListLocked(), imeId);
            }
        }
        if (failedToEnableUnknownIme) {
            error.print("Unknown input method ");
            error.print(imeId);
            error.println(" cannot be enabled for user #" + userId);
            return;
        }
        out.print("Input method ");
        out.print(imeId);
        out.print(": ");
        out.print(enabled == previouslyEnabled ? "already " : "now ");
        out.print(enabled ? "enabled" : "disabled");
        out.print(" for user #");
        out.println(userId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int handleShellCommandSetInputMethod(ShellCommand shellCommand) {
        int[] userIds;
        int userIdToBeResolved = handleOptionsForCommandsThatOnlyHaveUserOption(shellCommand);
        String imeId = shellCommand.getNextArgRequired();
        PrintWriter out = shellCommand.getOutPrintWriter();
        PrintWriter error = shellCommand.getErrPrintWriter();
        synchronized (this.mMethodMap) {
            int[] userIds2 = InputMethodUtils.resolveUserId(userIdToBeResolved, this.mSettings.getCurrentUserId(), shellCommand.getErrPrintWriter());
            int length = userIds2.length;
            int i = 0;
            while (i < length) {
                int userId = userIds2[i];
                if (!userHasDebugPriv(userId, shellCommand)) {
                    userIds = userIds2;
                } else {
                    boolean failedToSelectUnknownIme = false;
                    if (userId == this.mSettings.getCurrentUserId()) {
                        if (this.mMethodMap.containsKey(imeId)) {
                            setInputMethodLocked(imeId, -1);
                            userIds = userIds2;
                        } else {
                            failedToSelectUnknownIme = true;
                            userIds = userIds2;
                        }
                    } else {
                        ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
                        ArrayList<InputMethodInfo> methodList = new ArrayList<>();
                        ArrayMap<String, List<InputMethodSubtype>> additionalSubtypeMap = new ArrayMap<>();
                        AdditionalSubtypeUtils.load(additionalSubtypeMap, userId);
                        queryInputMethodServicesInternal(this.mContext, userId, additionalSubtypeMap, methodMap, methodList);
                        userIds = userIds2;
                        InputMethodUtils.InputMethodSettings settings = new InputMethodUtils.InputMethodSettings(this.mContext.getResources(), this.mContext.getContentResolver(), methodMap, userId, false);
                        if (methodMap.containsKey(imeId)) {
                            settings.putSelectedInputMethod(imeId);
                            settings.putSelectedSubtype(-1);
                        } else {
                            failedToSelectUnknownIme = true;
                        }
                    }
                    if (failedToSelectUnknownIme) {
                        error.print("Unknown input method ");
                        error.print(imeId);
                        error.print(" cannot be selected for user #");
                        error.println(userId);
                    } else {
                        out.print("Input method ");
                        out.print(imeId);
                        out.print(" selected for user #");
                        out.println(userId);
                    }
                }
                i++;
                userIds2 = userIds;
            }
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int handleShellCommandResetInputMethod(ShellCommand shellCommand) {
        List<InputMethodInfo> nextEnabledImes;
        String nextIme;
        final PrintWriter out = shellCommand.getOutPrintWriter();
        int userIdToBeResolved = handleOptionsForCommandsThatOnlyHaveUserOption(shellCommand);
        synchronized (this.mMethodMap) {
            try {
                int[] userIds = InputMethodUtils.resolveUserId(userIdToBeResolved, this.mSettings.getCurrentUserId(), shellCommand.getErrPrintWriter());
                int length = userIds.length;
                int i = 0;
                int i2 = 0;
                while (i2 < length) {
                    int userId = userIds[i2];
                    try {
                        if (userHasDebugPriv(userId, shellCommand)) {
                            if (userId == this.mSettings.getCurrentUserId()) {
                                hideCurrentInputLocked(i, null);
                                unbindCurrentMethodLocked();
                                resetSelectedInputMethodAndSubtypeLocked(null);
                                this.mSettings.putSelectedInputMethod(null);
                                this.mSettings.getEnabledInputMethodListLocked().forEach(new Consumer() { // from class: com.android.server.inputmethod.-$$Lambda$InputMethodManagerService$SkFx0gCz5ltIh90rm1gl_N-wDWM
                                    @Override // java.util.function.Consumer
                                    public final void accept(Object obj) {
                                        InputMethodManagerService.this.lambda$handleShellCommandResetInputMethod$1$InputMethodManagerService((InputMethodInfo) obj);
                                    }
                                });
                                InputMethodUtils.getDefaultEnabledImes(this.mContext, this.mMethodList).forEach(new Consumer() { // from class: com.android.server.inputmethod.-$$Lambda$InputMethodManagerService$-9-NV9-J24Jr9m-wcbQnLu0hhjU
                                    @Override // java.util.function.Consumer
                                    public final void accept(Object obj) {
                                        InputMethodManagerService.this.lambda$handleShellCommandResetInputMethod$2$InputMethodManagerService((InputMethodInfo) obj);
                                    }
                                });
                                updateInputMethodsFromSettingsLocked(true);
                                InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(this.mIPackageManager, this.mSettings.getEnabledInputMethodListLocked(), this.mSettings.getCurrentUserId(), this.mContext.getBasePackageName());
                                nextIme = this.mSettings.getSelectedInputMethod();
                                nextEnabledImes = this.mSettings.getEnabledInputMethodListLocked();
                            } else {
                                ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
                                ArrayList<InputMethodInfo> methodList = new ArrayList<>();
                                ArrayMap<String, List<InputMethodSubtype>> additionalSubtypeMap = new ArrayMap<>();
                                AdditionalSubtypeUtils.load(additionalSubtypeMap, userId);
                                queryInputMethodServicesInternal(this.mContext, userId, additionalSubtypeMap, methodMap, methodList);
                                final InputMethodUtils.InputMethodSettings settings = new InputMethodUtils.InputMethodSettings(this.mContext.getResources(), this.mContext.getContentResolver(), methodMap, userId, false);
                                nextEnabledImes = InputMethodUtils.getDefaultEnabledImes(this.mContext, methodList);
                                String nextIme2 = InputMethodUtils.getMostApplicableDefaultIME(nextEnabledImes).getId();
                                settings.putEnabledInputMethodsStr("");
                                nextEnabledImes.forEach(new Consumer() { // from class: com.android.server.inputmethod.-$$Lambda$InputMethodManagerService$cbEjGgC40X7HsuUviRQkKGegQKg
                                    @Override // java.util.function.Consumer
                                    public final void accept(Object obj) {
                                        InputMethodUtils.InputMethodSettings.this.appendAndPutEnabledInputMethodLocked(((InputMethodInfo) obj).getId(), false);
                                    }
                                });
                                settings.putSelectedInputMethod(nextIme2);
                                settings.putSelectedSubtype(-1);
                                nextIme = nextIme2;
                            }
                            out.println("Reset current and enabled IMEs for user #" + userId);
                            out.println("  Selected: " + nextIme);
                            nextEnabledImes.forEach(new Consumer() { // from class: com.android.server.inputmethod.-$$Lambda$InputMethodManagerService$yBUcRNgC_2SdMjBHdbSjb2l9-Rw
                                @Override // java.util.function.Consumer
                                public final void accept(Object obj) {
                                    InputMethodInfo inputMethodInfo = (InputMethodInfo) obj;
                                    out.println("   Enabled: " + inputMethodInfo.getId());
                                }
                            });
                        }
                        i2++;
                        i = 0;
                    } catch (Throwable th) {
                        th = th;
                        throw th;
                    }
                }
                return 0;
            } catch (Throwable th2) {
                th = th2;
            }
        }
    }

    public /* synthetic */ void lambda$handleShellCommandResetInputMethod$1$InputMethodManagerService(InputMethodInfo imi) {
        setInputMethodEnabledLocked(imi.getId(), false);
    }

    public /* synthetic */ void lambda$handleShellCommandResetInputMethod$2$InputMethodManagerService(InputMethodInfo imi) {
        setInputMethodEnabledLocked(imi.getId(), true);
    }

    private boolean userHasDebugPriv(int userId, ShellCommand shellCommand) {
        if (this.mUserManager.hasUserRestriction("no_debugging_features", UserHandle.of(userId))) {
            PrintWriter errPrintWriter = shellCommand.getErrPrintWriter();
            errPrintWriter.println("User #" + userId + " is restricted with DISALLOW_DEBUGGING_FEATURES.");
            return false;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class InputMethodPrivilegedOperationsImpl extends IInputMethodPrivilegedOperations.Stub {
        private final InputMethodManagerService mImms;
        private final IBinder mToken;

        InputMethodPrivilegedOperationsImpl(InputMethodManagerService imms, IBinder token) {
            this.mImms = imms;
            this.mToken = token;
        }

        public void setImeWindowStatus(int vis, int backDisposition) {
            this.mImms.setImeWindowStatus(this.mToken, vis, backDisposition);
        }

        public void reportStartInput(IBinder startInputToken) {
            this.mImms.reportStartInput(this.mToken, startInputToken);
        }

        public IInputContentUriToken createInputContentUriToken(Uri contentUri, String packageName) {
            return this.mImms.createInputContentUriToken(this.mToken, contentUri, packageName);
        }

        public void reportFullscreenMode(boolean fullscreen) {
            this.mImms.reportFullscreenMode(this.mToken, fullscreen);
        }

        public void setInputMethod(String id) {
            this.mImms.setInputMethod(this.mToken, id);
        }

        public void setInputMethodAndSubtype(String id, InputMethodSubtype subtype) {
            this.mImms.setInputMethodAndSubtype(this.mToken, id, subtype);
        }

        public void hideMySoftInput(int flags) {
            this.mImms.hideMySoftInput(this.mToken, flags);
        }

        public void showMySoftInput(int flags) {
            this.mImms.showMySoftInput(this.mToken, flags);
        }

        public void updateStatusIcon(String packageName, int iconId) {
            this.mImms.updateStatusIcon(this.mToken, packageName, iconId);
        }

        public boolean switchToPreviousInputMethod() {
            return this.mImms.switchToPreviousInputMethod(this.mToken);
        }

        public boolean switchToNextInputMethod(boolean onlyCurrentIme) {
            return this.mImms.switchToNextInputMethod(this.mToken, onlyCurrentIme);
        }

        public boolean shouldOfferSwitchingToNextInputMethod() {
            return this.mImms.shouldOfferSwitchingToNextInputMethod(this.mToken);
        }

        public void notifyUserAction() {
            this.mImms.notifyUserAction(this.mToken);
        }

        public void reportPreRendered(EditorInfo info) {
            this.mImms.reportPreRendered(this.mToken, info);
        }

        public void applyImeVisibility(boolean setVisible) {
            this.mImms.applyImeVisibility(this.mToken, setVisible);
        }
    }
}
