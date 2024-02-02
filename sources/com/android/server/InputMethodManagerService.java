package com.android.server;

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
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
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
import android.provider.Settings;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.text.TextUtils;
import android.text.style.SuggestionSpan;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.EventLog;
import android.util.LruCache;
import android.util.Pair;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.Xml;
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
import android.view.inputmethod.InputMethodManagerInternal;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.inputmethod.IInputContentUriToken;
import com.android.internal.inputmethod.InputMethodSubtypeSwitchingController;
import com.android.internal.inputmethod.InputMethodUtils;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.TransferPipe;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethod;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.view.IInputSessionCallback;
import com.android.internal.view.InputBindResult;
import com.android.internal.view.InputMethodClient;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.pm.DumpState;
import com.android.server.statusbar.StatusBarManagerService;
import com.android.server.wm.WindowManagerInternal;
import com.xiaopeng.server.input.xpInputManagerService;
import com.xiaopeng.util.xpSettings;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.InvalidParameterException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
/* loaded from: classes.dex */
public class InputMethodManagerService extends IInputMethodManager.Stub implements ServiceConnection, Handler.Callback {
    private static final String ACTION_SHOW_INPUT_METHOD_PICKER = "com.android.server.InputMethodManagerService.SHOW_INPUT_METHOD_PICKER";
    static final boolean DEBUG = true;
    private static final int IME_CONNECTION_BIND_FLAGS = 1082130437;
    private static final int IME_VISIBLE_BIND_FLAGS = 738197505;
    static final int MSG_ATTACH_TOKEN = 1040;
    static final int MSG_BIND_CLIENT = 3010;
    static final int MSG_BIND_INPUT = 1010;
    static final int MSG_CREATE_SESSION = 1050;
    static final int MSG_HARD_KEYBOARD_SWITCH_CHANGED = 4000;
    static final int MSG_HIDE_CURRENT_INPUT_METHOD = 1035;
    static final int MSG_HIDE_SOFT_INPUT = 1030;
    static final int MSG_REPORT_FULLSCREEN_MODE = 3045;
    static final int MSG_SET_ACTIVE = 3020;
    static final int MSG_SET_INTERACTIVE = 3030;
    static final int MSG_SET_USER_ACTION_NOTIFICATION_SEQUENCE_NUMBER = 3040;
    static final int MSG_SHOW_IM_CONFIG = 3;
    static final int MSG_SHOW_IM_SUBTYPE_ENABLER = 2;
    static final int MSG_SHOW_IM_SUBTYPE_PICKER = 1;
    static final int MSG_SHOW_SOFT_INPUT = 1020;
    static final int MSG_START_INPUT = 2000;
    static final int MSG_START_VR_INPUT = 2010;
    static final int MSG_SWITCH_IME = 3050;
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
    private InputMethodFileManager mFileManager;
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
    private LocaleList mLastSystemLocales;
    private NotificationManager mNotificationManager;
    private boolean mNotificationShown;
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
    private Toast mSubtypeSwitchedByShortCutToast;
    private final InputMethodSubtypeSwitchingController mSwitchingController;
    private AlertDialog mSwitchingDialog;
    private View mSwitchingDialogTitleView;
    boolean mSystemReady;
    private final UserManager mUserManager;
    final ArrayList<InputMethodInfo> mMethodList = new ArrayList<>();
    final HashMap<String, InputMethodInfo> mMethodMap = new HashMap<>();
    private final LruCache<SuggestionSpan, InputMethodInfo> mSecureSuggestionSpans = new LruCache<>(20);
    @GuardedBy("mMethodMap")
    private int mMethodMapUpdateCount = 0;
    final ServiceConnection mVisibleConnection = new ServiceConnection() { // from class: com.android.server.InputMethodManagerService.1
        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
        }
    };
    boolean mVisibleBound = false;
    private final Object mUnBindLock = new Object();
    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() { // from class: com.android.server.InputMethodManagerService.2
        public void onVrStateChanged(boolean enabled) {
            if (!enabled) {
                InputMethodManagerService.this.restoreNonVrImeFromSettingsNoCheck();
            }
        }
    };
    final HashMap<IBinder, ClientState> mClients = new HashMap<>();
    private final HashMap<InputMethodInfo, ArrayList<InputMethodSubtype>> mShortcutInputMethodsAndSubtypes = new HashMap<>();
    boolean mIsInteractive = true;
    int mCurUserActionNotificationSequenceNumber = 0;
    int mBackDisposition = 0;
    private IBinder mSwitchingDialogToken = new Binder();
    private final MyPackageMonitor mMyPackageMonitor = new MyPackageMonitor();
    private boolean mBindInstantServiceAllowed = false;
    @GuardedBy("mMethodMap")
    private final WeakHashMap<IBinder, StartInputInfo> mStartInputMap = new WeakHashMap<>();
    @GuardedBy("mMethodMap")
    private final StartInputHistory mStartInputHistory = new StartInputHistory();
    private final IPackageManager mIPackageManager = AppGlobals.getPackageManager();
    final Handler mHandler = new Handler(this);
    final SettingsObserver mSettingsObserver = new SettingsObserver(this.mHandler);
    final IWindowManager mIWindowManager = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
    final WindowManagerInternal mWindowManagerInternal = (WindowManagerInternal) LocalServices.getService(WindowManagerInternal.class);
    private final HardKeyboardListener mHardKeyboardListener = new HardKeyboardListener();

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    private @interface HardKeyboardBehavior {
        public static final int WIRED_AFFORDANCE = 1;
        public static final int WIRELESS_AFFORDANCE = 0;
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
        @GuardedBy("LOCK")
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
    public void restoreNonVrImeFromSettingsNoCheck() {
        synchronized (this.mMethodMap) {
            String lastInputId = this.mSettings.getSelectedInputMethod();
            setInputMethodLocked(lastInputId, this.mSettings.getSelectedInputMethodSubtypeId(lastInputId));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class ClientState {
        final InputBinding binding;
        final IInputMethodClient client;
        SessionState curSession;
        final IInputContext inputContext;
        final int pid;
        boolean sessionRequested;
        final int uid;

        public String toString() {
            return "ClientState{" + Integer.toHexString(System.identityHashCode(this)) + " uid " + this.uid + " pid " + this.pid + "}";
        }

        ClientState(IInputMethodClient _client, IInputContext _inputContext, int _uid, int _pid) {
            this.client = _client;
            this.inputContext = _inputContext;
            this.uid = _uid;
            this.pid = _pid;
            this.binding = new InputBinding(null, this.inputContext.asBinder(), this.uid, this.pid);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class StartInputInfo {
        private static final AtomicInteger sSequenceNumber = new AtomicInteger(0);
        final int mClientBindSequenceNumber;
        final EditorInfo mEditorInfo;
        final String mImeId;
        final IBinder mImeToken;
        final boolean mRestarting;
        final int mStartInputReason;
        final IBinder mTargetWindow;
        final int mTargetWindowSoftInputMode;
        final int mSequenceNumber = sSequenceNumber.getAndIncrement();
        final long mTimestamp = SystemClock.uptimeMillis();
        final long mWallTime = System.currentTimeMillis();

        StartInputInfo(IBinder imeToken, String imeId, int startInputReason, boolean restarting, IBinder targetWindow, EditorInfo editorInfo, int targetWindowSoftInputMode, int clientBindSequenceNumber) {
            this.mImeToken = imeToken;
            this.mImeId = imeId;
            this.mStartInputReason = startInputReason;
            this.mRestarting = restarting;
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
            String mImeId;
            String mImeTokenString;
            boolean mRestarting;
            int mSequenceNumber;
            int mStartInputReason;
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
                this.mImeTokenString = String.valueOf(original.mImeToken);
                this.mImeId = original.mImeId;
                this.mStartInputReason = original.mStartInputReason;
                this.mRestarting = original.mRestarting;
                this.mTargetWindowString = String.valueOf(original.mTargetWindow);
                this.mEditorInfo = original.mEditorInfo;
                this.mTargetWindowSoftInputMode = original.mTargetWindowSoftInputMode;
                this.mClientBindSequenceNumber = original.mClientBindSequenceNumber;
            }
        }

        void addEntry(StartInputInfo info) {
            int index = this.mNextIndex;
            if (this.mEntries[index] == null) {
                this.mEntries[index] = new Entry(info);
            } else {
                this.mEntries[index].set(info);
            }
            this.mNextIndex = (this.mNextIndex + 1) % this.mEntries.length;
        }

        void dump(PrintWriter pw, String prefix) {
            SimpleDateFormat dataFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
            for (int i = 0; i < this.mEntries.length; i++) {
                Entry entry = this.mEntries[(this.mNextIndex + i) % this.mEntries.length];
                if (entry != null) {
                    pw.print(prefix);
                    pw.println("StartInput #" + entry.mSequenceNumber + ":");
                    pw.print(prefix);
                    pw.println(" time=" + dataFormat.format(new Date(entry.mWallTime)) + " (timestamp=" + entry.mTimestamp + ") reason=" + InputMethodClient.getStartInputReason(entry.mStartInputReason) + " restarting=" + entry.mRestarting);
                    pw.print(prefix);
                    StringBuilder sb = new StringBuilder();
                    sb.append(" imeToken=");
                    sb.append(entry.mImeTokenString);
                    sb.append(" [");
                    sb.append(entry.mImeId);
                    sb.append("]");
                    pw.println(sb.toString());
                    pw.print(prefix);
                    pw.println(" targetWin=" + entry.mTargetWindowString + " [" + entry.mEditorInfo.packageName + "] clientBindSeq=" + entry.mClientBindSequenceNumber);
                    pw.print(prefix);
                    StringBuilder sb2 = new StringBuilder();
                    sb2.append(" softInputMode=");
                    sb2.append(InputMethodClient.softInputModeToString(entry.mTargetWindowSoftInputMode));
                    pw.println(sb2.toString());
                    pw.print(prefix);
                    pw.println(" inputType=0x" + Integer.toHexString(entry.mEditorInfo.inputType) + " imeOptions=0x" + Integer.toHexString(entry.mEditorInfo.imeOptions) + " fieldId=0x" + Integer.toHexString(entry.mEditorInfo.fieldId) + " fieldName=" + entry.mEditorInfo.fieldName + " actionId=" + entry.mEditorInfo.actionId + " actionLabel=" + ((Object) entry.mEditorInfo.actionLabel));
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
            this.mLastEnabled = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
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
                this.mLastEnabled = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
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
                    InputMethodManagerService.this.mAccessibilityRequestingNoSoftKeyboard = Settings.Secure.getIntForUser(InputMethodManagerService.this.mContext.getContentResolver(), "accessibility_soft_keyboard_mode", 0, this.mUserId) == 1;
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

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class ImmsBroadcastReceiver extends BroadcastReceiver {
        ImmsBroadcastReceiver() {
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("android.intent.action.CLOSE_SYSTEM_DIALOGS".equals(action)) {
                InputMethodManagerService.this.hideInputMethodMenu();
            } else if ("android.intent.action.USER_ADDED".equals(action) || "android.intent.action.USER_REMOVED".equals(action)) {
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

    private void startVrInputMethodNoCheck(ComponentName component) {
        if (component == null) {
            restoreNonVrImeFromSettingsNoCheck();
            return;
        }
        synchronized (this.mMethodMap) {
            String packageName = component.getPackageName();
            Iterator<InputMethodInfo> it = this.mMethodList.iterator();
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                InputMethodInfo info = it.next();
                if (TextUtils.equals(info.getPackageName(), packageName) && info.isVrOnly()) {
                    setInputMethodEnabledLocked(info.getId(), true);
                    setInputMethodLocked(info.getId(), -1);
                    break;
                }
            }
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
        @GuardedBy("mMethodMap")
        private final ArraySet<String> mKnownImePackageNames = new ArraySet<>();
        private final ArrayList<String> mChangedPackages = new ArrayList<>();
        private boolean mImePackageAppeared = false;

        MyPackageMonitor() {
        }

        @GuardedBy("mMethodMap")
        void clearKnownImePackageNamesLocked() {
            this.mKnownImePackageNames.clear();
        }

        @GuardedBy("mMethodMap")
        final void addKnownImePackageNameLocked(String packageName) {
            this.mKnownImePackageNames.add(packageName);
        }

        @GuardedBy("mMethodMap")
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
                                            InputMethodManagerService.this.resetSelectedInputMethodAndSubtypeLocked(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
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
                List<ResolveInfo> services = pm.queryIntentServicesAsUser(new Intent("android.view.InputMethod").setPackage(packageName), InputMethodManagerService.this.getComponentMatchingFlags(512), getChangingUserId());
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

        @GuardedBy("mMethodMap")
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
                            InputMethodInfo curIm2 = null;
                            for (int i = 0; i < N; i++) {
                                InputMethodInfo imi = InputMethodManagerService.this.mMethodList.get(i);
                                String imiId = imi.getId();
                                if (imiId.equals(curInputMethodId)) {
                                    curIm2 = imi;
                                }
                                int change2 = isPackageDisappearing(imi.getPackageName());
                                if (isPackageModified(imi.getPackageName())) {
                                    InputMethodManagerService.this.mFileManager.deleteAllInputMethodSubtypes(imiId);
                                }
                                if (change2 == 2 || change2 == 3) {
                                    Slog.i(InputMethodManagerService.TAG, "Input method uninstalled, disabling: " + imi.getComponent());
                                    InputMethodManagerService.this.setInputMethodEnabledLocked(imi.getId(), false);
                                }
                            }
                            curIm = curIm2;
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
                                InputMethodManagerService.this.updateSystemUiLocked(InputMethodManagerService.this.mCurToken, 0, InputMethodManagerService.this.mBackDisposition);
                                if (!InputMethodManagerService.this.chooseNewDefaultIMELocked()) {
                                    changed = true;
                                    curIm = null;
                                    Slog.i(InputMethodManagerService.TAG, "Unsetting current input method");
                                    InputMethodManagerService.this.resetSelectedInputMethodAndSubtypeLocked(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
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
                    InputMethodManagerService.this.mSwitchingDialogTitleView.findViewById(16909026).setVisibility(available ? 0 : 8);
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
            LocalServices.addService(InputMethodManagerInternal.class, new LocalServiceImpl(this.mService.mHandler));
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
            this.mService.mHandler.sendMessage(this.mService.mHandler.obtainMessage(InputMethodManagerService.MSG_SYSTEM_UNLOCK_USER, userHandle, 0));
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
        this.mCaller = new HandlerCaller(context, (Looper) null, new HandlerCaller.Callback() { // from class: com.android.server.InputMethodManagerService.3
            public void executeMessage(Message msg) {
                InputMethodManagerService.this.handleMessage(msg);
            }
        }, true);
        this.mAppOpsManager = (AppOpsManager) this.mContext.getSystemService(AppOpsManager.class);
        this.mUserManager = (UserManager) this.mContext.getSystemService(UserManager.class);
        this.mHasFeature = context.getPackageManager().hasSystemFeature("android.software.input_methods");
        this.mSlotIme = this.mContext.getString(17040933);
        this.mHardKeyboardBehavior = this.mContext.getResources().getInteger(17694786);
        Bundle extras = new Bundle();
        extras.putBoolean("android.allowDuringSetup", true);
        int accentColor = this.mContext.getColor(17170861);
        this.mImeSwitcherNotification = new Notification.Builder(this.mContext, SystemNotificationChannels.VIRTUAL_KEYBOARD).setSmallIcon(17302697).setWhen(0L).setOngoing(true).addExtras(extras).setCategory("sys").setColor(accentColor);
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
        this.mSettings = new InputMethodUtils.InputMethodSettings(this.mRes, context.getContentResolver(), this.mMethodMap, this.mMethodList, userId, !this.mSystemReady);
        updateCurrentProfileIds();
        this.mFileManager = new InputMethodFileManager(this.mMethodMap, userId);
        this.mSwitchingController = InputMethodSubtypeSwitchingController.createInstanceLocked(this.mSettings, context);
        IVrManager vrManager = ServiceManager.getService("vrmanager");
        if (vrManager != null) {
            try {
                vrManager.registerListener(this.mVrStateCallbacks);
            } catch (RemoteException e2) {
                Slog.e(TAG, "Failed to register VR mode state listener.");
            }
        }
    }

    private void resetDefaultImeLocked(Context context) {
        if (this.mCurMethodId != null && !InputMethodUtils.isSystemIme(this.mMethodMap.get(this.mCurMethodId))) {
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

    @GuardedBy("mMethodMap")
    private void switchUserLocked(int newUserId) {
        Slog.d(TAG, "Switching user stage 1/3. newUserId=" + newUserId + " currentUserId=" + this.mSettings.getCurrentUserId());
        this.mSettingsObserver.registerContentObserverLocked(newUserId);
        boolean useCopyOnWriteSettings = (this.mSystemReady && this.mUserManager.isUserUnlockingOrUnlocked(newUserId)) ? false : true;
        this.mSettings.switchCurrentUser(newUserId, useCopyOnWriteSettings);
        updateCurrentProfileIds();
        this.mFileManager = new InputMethodFileManager(this.mMethodMap, newUserId);
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
            try {
                startInputInnerLocked();
            } catch (RuntimeException e) {
                Slog.w(TAG, "Unexpected exception", e);
            }
        }
        if (initialUserSwitch) {
            InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(this.mIPackageManager, this.mSettings.getEnabledInputMethodListLocked(), newUserId, this.mContext.getBasePackageName());
        }
        Slog.d(TAG, "Switching user stage 3/3. newUserId=" + newUserId + " selectedIme=" + this.mSettings.getSelectedInputMethod());
    }

    void updateCurrentProfileIds() {
        this.mSettings.setCurrentProfileIds(this.mUserManager.getProfileIdsWithDisabled(this.mSettings.getCurrentUserId()));
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
                this.mSettings.switchCurrentUser(currentUserId, !this.mUserManager.isUserUnlockingOrUnlocked(currentUserId));
                this.mKeyguardManager = (KeyguardManager) this.mContext.getSystemService(KeyguardManager.class);
                this.mNotificationManager = (NotificationManager) this.mContext.getSystemService(NotificationManager.class);
                this.mStatusBar = statusBar;
                if (this.mStatusBar != null) {
                    this.mStatusBar.setIconVisibility(this.mSlotIme, false);
                }
                updateSystemUiLocked(this.mCurToken, this.mImeWindowVis, this.mBackDisposition);
                this.mShowOngoingImeSwitcherForPhones = this.mRes.getBoolean(17957115);
                if (this.mShowOngoingImeSwitcherForPhones) {
                    this.mWindowManagerInternal.setOnHardKeyboardStatusChangeListener(this.mHardKeyboardListener);
                }
                this.mMyPackageMonitor.register(this.mContext, null, UserHandle.ALL, true);
                this.mSettingsObserver.registerContentObserverLocked(currentUserId);
                IntentFilter broadcastFilter = new IntentFilter();
                broadcastFilter.addAction("android.intent.action.CLOSE_SYSTEM_DIALOGS");
                broadcastFilter.addAction("android.intent.action.USER_ADDED");
                broadcastFilter.addAction("android.intent.action.USER_REMOVED");
                broadcastFilter.addAction("android.intent.action.LOCALE_CHANGED");
                broadcastFilter.addAction(ACTION_SHOW_INPUT_METHOD_PICKER);
                this.mContext.registerReceiver(new ImmsBroadcastReceiver(), broadcastFilter);
                String defaultImiId = this.mSettings.getSelectedInputMethod();
                boolean imeSelectedOnBoot = !TextUtils.isEmpty(defaultImiId);
                buildInputMethodListLocked(imeSelectedOnBoot ? false : true);
                resetDefaultImeLocked(this.mContext);
                updateFromSettingsLocked(true);
                InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(this.mIPackageManager, this.mSettings.getEnabledInputMethodListLocked(), currentUserId, this.mContext.getBasePackageName());
                try {
                    startInputInnerLocked();
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Unexpected exception", e);
                }
                setInputMethodShownToSettings(this.mContext, this.mInputShown);
            }
        }
    }

    private boolean calledFromValidUser() {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getUserId(uid);
        Slog.d(TAG, "--- calledFromForegroundUserOrSystemProcess ? calling uid = " + uid + " system uid = 1000 calling userId = " + userId + ", foreground user id = " + this.mSettings.getCurrentUserId() + ", calling pid = " + Binder.getCallingPid() + InputMethodUtils.getApiCallStack());
        if (uid == 1000 || this.mSettings.isCurrentProfile(userId)) {
            return true;
        }
        if (this.mContext.checkCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL") == 0) {
            Slog.d(TAG, "--- Access granted because the calling process has the INTERACT_ACROSS_USERS_FULL permission");
            return true;
        }
        Slog.w(TAG, "--- IPC called from background users. Ignore. callers=" + Debug.getCallers(10));
        return false;
    }

    private boolean calledWithValidToken(IBinder token) {
        if (token == null && Binder.getCallingPid() == Process.myPid()) {
            Slog.d(TAG, "Bug 34851776 is detected callers=" + Debug.getCallers(10));
            return false;
        } else if (token == null || token != this.mCurToken) {
            Slog.e(TAG, "Ignoring " + Debug.getCaller() + " due to an invalid token. uid:" + Binder.getCallingUid() + " token:" + token);
            return false;
        } else {
            return true;
        }
    }

    @GuardedBy("mMethodMap")
    private boolean bindCurrentInputMethodServiceLocked(Intent service, ServiceConnection conn, int flags) {
        if (service == null || conn == null) {
            Slog.e(TAG, "--- bind failed: service = " + service + ", conn = " + conn);
            return false;
        }
        if (this.mBindInstantServiceAllowed) {
            flags |= DumpState.DUMP_CHANGES;
        }
        return this.mContext.bindServiceAsUser(service, conn, flags, new UserHandle(this.mSettings.getCurrentUserId()));
    }

    public List<InputMethodInfo> getInputMethodList() {
        return getInputMethodList(false);
    }

    public List<InputMethodInfo> getVrInputMethodList() {
        return getInputMethodList(true);
    }

    private List<InputMethodInfo> getInputMethodList(boolean isVrOnly) {
        ArrayList<InputMethodInfo> methodList;
        if (!calledFromValidUser()) {
            return Collections.emptyList();
        }
        synchronized (this.mMethodMap) {
            methodList = new ArrayList<>();
            Iterator<InputMethodInfo> it = this.mMethodList.iterator();
            while (it.hasNext()) {
                InputMethodInfo info = it.next();
                if (info.isVrOnly() == isVrOnly) {
                    methodList.add(info);
                }
            }
        }
        return methodList;
    }

    public List<InputMethodInfo> getEnabledInputMethodList() {
        ArrayList enabledInputMethodListLocked;
        if (!calledFromValidUser()) {
            return Collections.emptyList();
        }
        synchronized (this.mMethodMap) {
            enabledInputMethodListLocked = this.mSettings.getEnabledInputMethodListLocked();
        }
        return enabledInputMethodListLocked;
    }

    /* JADX WARN: Removed duplicated region for block: B:16:0x002b A[Catch: all -> 0x001f, TryCatch #0 {all -> 0x001f, blocks: (B:9:0x0010, B:11:0x0014, B:16:0x002b, B:17:0x002f, B:19:0x0031, B:20:0x0039, B:14:0x0021), top: B:24:0x0010 }] */
    /* JADX WARN: Removed duplicated region for block: B:19:0x0031 A[Catch: all -> 0x001f, TryCatch #0 {all -> 0x001f, blocks: (B:9:0x0010, B:11:0x0014, B:16:0x002b, B:17:0x002f, B:19:0x0031, B:20:0x0039, B:14:0x0021), top: B:24:0x0010 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public java.util.List<android.view.inputmethod.InputMethodSubtype> getEnabledInputMethodSubtypeList(java.lang.String r5, boolean r6) {
        /*
            r4 = this;
            boolean r0 = r4.calledFromValidUser()
            if (r0 != 0) goto Lb
            java.util.List r0 = java.util.Collections.emptyList()
            return r0
        Lb:
            java.util.HashMap<java.lang.String, android.view.inputmethod.InputMethodInfo> r0 = r4.mMethodMap
            monitor-enter(r0)
            if (r5 != 0) goto L21
            java.lang.String r1 = r4.mCurMethodId     // Catch: java.lang.Throwable -> L1f
            if (r1 == 0) goto L21
            java.util.HashMap<java.lang.String, android.view.inputmethod.InputMethodInfo> r1 = r4.mMethodMap     // Catch: java.lang.Throwable -> L1f
            java.lang.String r2 = r4.mCurMethodId     // Catch: java.lang.Throwable -> L1f
            java.lang.Object r1 = r1.get(r2)     // Catch: java.lang.Throwable -> L1f
            android.view.inputmethod.InputMethodInfo r1 = (android.view.inputmethod.InputMethodInfo) r1     // Catch: java.lang.Throwable -> L1f
            goto L29
        L1f:
            r1 = move-exception
            goto L3b
        L21:
            java.util.HashMap<java.lang.String, android.view.inputmethod.InputMethodInfo> r1 = r4.mMethodMap     // Catch: java.lang.Throwable -> L1f
            java.lang.Object r1 = r1.get(r5)     // Catch: java.lang.Throwable -> L1f
            android.view.inputmethod.InputMethodInfo r1 = (android.view.inputmethod.InputMethodInfo) r1     // Catch: java.lang.Throwable -> L1f
        L29:
            if (r1 != 0) goto L31
            java.util.List r2 = java.util.Collections.emptyList()     // Catch: java.lang.Throwable -> L1f
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L1f
            return r2
        L31:
            com.android.internal.inputmethod.InputMethodUtils$InputMethodSettings r2 = r4.mSettings     // Catch: java.lang.Throwable -> L1f
            android.content.Context r3 = r4.mContext     // Catch: java.lang.Throwable -> L1f
            java.util.List r2 = r2.getEnabledInputMethodSubtypeListLocked(r3, r1, r6)     // Catch: java.lang.Throwable -> L1f
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L1f
            return r2
        L3b:
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L1f
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.InputMethodManagerService.getEnabledInputMethodSubtypeList(java.lang.String, boolean):java.util.List");
    }

    public void addClient(IInputMethodClient client, IInputContext inputContext, int uid, int pid) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (this.mMethodMap) {
            this.mClients.put(client.asBinder(), new ClientState(client, inputContext, uid, pid));
        }
    }

    public void removeClient(IInputMethodClient client) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (this.mMethodMap) {
            ClientState cs = this.mClients.remove(client.asBinder());
            if (cs != null) {
                clearClientSessionLocked(cs);
                if (this.mCurClient == cs) {
                    if (this.mBoundToMethod) {
                        this.mBoundToMethod = false;
                        if (this.mCurMethod != null) {
                            executeOrSendMessage(this.mCurMethod, this.mCaller.obtainMessageO(1000, this.mCurMethod));
                        }
                    }
                    this.mCurClient = null;
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
                if (this.mCurMethod != null) {
                    executeOrSendMessage(this.mCurMethod, this.mCaller.obtainMessageO(1000, this.mCurMethod));
                }
            }
            executeOrSendMessage(this.mCurClient.client, this.mCaller.obtainMessageIIO((int) MSG_SET_ACTIVE, 0, 0, this.mCurClient));
            executeOrSendMessage(this.mCurClient.client, this.mCaller.obtainMessageIIO((int) MSG_UNBIND_CLIENT, this.mCurSeq, unbindClientReason, this.mCurClient.client));
            this.mCurClient.sessionRequested = false;
            this.mCurClient = null;
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

    @GuardedBy("mMethodMap")
    InputBindResult attachNewInputLocked(int startInputReason, boolean initial) {
        if (!this.mBoundToMethod) {
            executeOrSendMessage(this.mCurMethod, this.mCaller.obtainMessageOO((int) MSG_BIND_INPUT, this.mCurMethod, this.mCurClient.binding));
            this.mBoundToMethod = true;
        }
        Binder startInputToken = new Binder();
        StartInputInfo info = new StartInputInfo(this.mCurToken, this.mCurId, startInputReason, !initial ? 1 : 0, this.mCurFocusedWindow, this.mCurAttribute, this.mCurFocusedWindowSoftInputMode, this.mCurSeq);
        this.mStartInputMap.put(startInputToken, info);
        this.mStartInputHistory.addEntry(info);
        SessionState session = this.mCurClient.curSession;
        executeOrSendMessage(session.method, this.mCaller.obtainMessageIIOOOO((int) MSG_START_INPUT, this.mCurInputContextMissingMethods, !initial ? 1 : 0, startInputToken, session, this.mCurInputContext, this.mCurAttribute));
        if (this.mShowRequested) {
            Slog.v(TAG, "Attach new input asks to show input");
            showCurrentInputLocked(getAppShowFlags(), null);
        }
        return new InputBindResult(0, session.session, session.channel != null ? session.channel.dup() : null, this.mCurId, this.mCurSeq, this.mCurUserActionNotificationSequenceNumber);
    }

    @GuardedBy("mMethodMap")
    InputBindResult startInputLocked(int startInputReason, IInputMethodClient client, IInputContext inputContext, int missingMethods, EditorInfo attribute, int controlFlags) {
        if (this.mCurMethodId == null) {
            return InputBindResult.NO_IME;
        }
        ClientState cs = this.mClients.get(client.asBinder());
        if (cs == null) {
            throw new IllegalArgumentException("unknown client " + client.asBinder());
        } else if (attribute == null) {
            Slog.w(TAG, "Ignoring startInput with null EditorInfo. uid=" + cs.uid + " pid=" + cs.pid);
            return InputBindResult.NULL_EDITOR_INFO;
        } else {
            try {
                if (!this.mIWindowManager.inputMethodClientHasFocus(cs.client)) {
                    Slog.w(TAG, "Starting input on non-focused client " + cs.client + " (uid=" + cs.uid + " pid=" + cs.pid + ")");
                    return InputBindResult.NOT_IME_TARGET_WINDOW;
                }
            } catch (RemoteException e) {
            }
            return startInputUncheckedLocked(cs, inputContext, missingMethods, attribute, controlFlags, startInputReason);
        }
    }

    @GuardedBy("mMethodMap")
    InputBindResult startInputUncheckedLocked(ClientState cs, IInputContext inputContext, int missingMethods, EditorInfo attribute, int controlFlags, int startInputReason) {
        if (this.mCurMethodId == null) {
            return InputBindResult.NO_IME;
        }
        if (!InputMethodUtils.checkIfPackageBelongsToUid(this.mAppOpsManager, cs.uid, attribute.packageName)) {
            Slog.e(TAG, "Rejecting this client as it reported an invalid package name. uid=" + cs.uid + " package=" + attribute.packageName);
            return InputBindResult.INVALID_PACKAGE_NAME;
        }
        if (this.mCurClient != cs) {
            this.mCurClientInKeyguard = isKeyguardLocked();
            unbindCurrentClientLocked(1);
            Slog.v(TAG, "switching to client: client=" + cs.client.asBinder() + " keyguard=" + this.mCurClientInKeyguard);
            if (this.mIsInteractive) {
                executeOrSendMessage(cs.client, this.mCaller.obtainMessageIO((int) MSG_SET_ACTIVE, this.mIsInteractive ? 1 : 0, cs));
            }
        }
        this.mCurSeq++;
        if (this.mCurSeq <= 0) {
            this.mCurSeq = 1;
        }
        this.mCurClient = cs;
        this.mCurInputContext = inputContext;
        this.mCurInputContextMissingMethods = missingMethods;
        this.mCurAttribute = attribute;
        if (this.mCurId != null && this.mCurId.equals(this.mCurMethodId)) {
            if (cs.curSession != null) {
                return attachNewInputLocked(startInputReason, (controlFlags & 256) != 0);
            } else if (this.mHaveConnection) {
                if (this.mCurMethod != null) {
                    requestClientSessionLocked(cs);
                    return new InputBindResult(1, (IInputMethodSession) null, (InputChannel) null, this.mCurId, this.mCurSeq, this.mCurUserActionNotificationSequenceNumber);
                } else if (SystemClock.uptimeMillis() < this.mLastBindTime + TIME_TO_RECONNECT) {
                    return new InputBindResult(2, (IInputMethodSession) null, (InputChannel) null, this.mCurId, this.mCurSeq, this.mCurUserActionNotificationSequenceNumber);
                } else {
                    EventLog.writeEvent((int) EventLogTags.IMF_FORCE_RECONNECT_IME, this.mCurMethodId, Long.valueOf(SystemClock.uptimeMillis() - this.mLastBindTime), 0);
                }
            }
        }
        return startInputInnerLocked();
    }

    InputBindResult startInputInnerLocked() {
        if (this.mCurMethodId == null) {
            return InputBindResult.NO_IME;
        }
        if (!this.mSystemReady) {
            return new InputBindResult(7, (IInputMethodSession) null, (InputChannel) null, this.mCurMethodId, this.mCurSeq, this.mCurUserActionNotificationSequenceNumber);
        }
        InputMethodInfo info = this.mMethodMap.get(this.mCurMethodId);
        if (info == null) {
            throw new IllegalArgumentException("Unknown id: " + this.mCurMethodId);
        }
        unbindCurrentMethodLocked(true);
        this.mCurIntent = new Intent("android.view.InputMethod");
        this.mCurIntent.setComponent(info.getComponent());
        this.mCurIntent.putExtra("android.intent.extra.client_label", 17040043);
        this.mCurIntent.putExtra("android.intent.extra.client_intent", PendingIntent.getActivity(this.mContext, 0, new Intent("android.settings.INPUT_METHOD_SETTINGS"), 0));
        if (bindCurrentInputMethodServiceLocked(this.mCurIntent, this, IME_CONNECTION_BIND_FLAGS)) {
            this.mLastBindTime = SystemClock.uptimeMillis();
            this.mHaveConnection = true;
            this.mCurId = info.getId();
            this.mCurToken = new Binder();
            try {
                Slog.v(TAG, "Adding window token: " + this.mCurToken);
                this.mIWindowManager.addWindowToken(this.mCurToken, 2011, 0);
            } catch (RemoteException e) {
            }
            return new InputBindResult(2, (IInputMethodSession) null, (InputChannel) null, this.mCurId, this.mCurSeq, this.mCurUserActionNotificationSequenceNumber);
        }
        this.mCurIntent = null;
        Slog.w(TAG, "Failure connecting to input method service: " + this.mCurIntent);
        return InputBindResult.IME_NOT_CONNECTED;
    }

    private InputBindResult startInput(int startInputReason, IInputMethodClient client, IInputContext inputContext, int missingMethods, EditorInfo attribute, int controlFlags) {
        InputBindResult startInputLocked;
        if (!calledFromValidUser()) {
            return InputBindResult.INVALID_USER;
        }
        synchronized (this.mMethodMap) {
            Slog.v(TAG, "startInput: reason=" + InputMethodClient.getStartInputReason(startInputReason) + " client = " + client.asBinder() + " inputContext=" + inputContext + " missingMethods=" + InputConnectionInspector.getMissingMethodFlagsAsString(missingMethods) + " attribute=" + attribute + " controlFlags=#" + Integer.toHexString(controlFlags));
            long ident = Binder.clearCallingIdentity();
            startInputLocked = startInputLocked(startInputReason, client, inputContext, missingMethods, attribute, controlFlags);
            Binder.restoreCallingIdentity(ident);
        }
        return startInputLocked;
    }

    public void finishInput(IInputMethodClient client) {
    }

    @Override // android.content.ServiceConnection
    public void onServiceConnected(ComponentName name, IBinder service) {
        synchronized (this.mMethodMap) {
            if (this.mCurIntent != null && name.equals(this.mCurIntent.getComponent())) {
                this.mCurMethod = IInputMethod.Stub.asInterface(service);
                if (this.mCurToken == null) {
                    Slog.w(TAG, "Service connected without a token!");
                    unbindCurrentMethodLocked(false);
                    return;
                }
                Slog.v(TAG, "Initiating attach with token: " + this.mCurToken);
                executeOrSendMessage(this.mCurMethod, this.mCaller.obtainMessageOO((int) MSG_ATTACH_TOKEN, this.mCurMethod, this.mCurToken));
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

    void unbindCurrentMethodLocked(boolean savePosition) {
        synchronized (this.mUnBindLock) {
            if (this.mVisibleBound) {
                Slog.d(TAG, "unbindCurrentMethodLocked() mVisibleBound");
                this.mContext.unbindService(this.mVisibleConnection);
                this.mVisibleBound = false;
            }
        }
        if (this.mHaveConnection) {
            Slog.d(TAG, "unbindCurrentMethodLocked() mHaveConnection");
            this.mContext.unbindService(this);
            this.mHaveConnection = false;
        }
        if (this.mCurToken != null) {
            try {
                Slog.v(TAG, "Removing window token: " + this.mCurToken);
                if ((this.mImeWindowVis & 1) != 0 && savePosition) {
                    this.mWindowManagerInternal.saveLastInputMethodWindowForTransition();
                }
                this.mIWindowManager.removeWindowToken(this.mCurToken, 0);
            } catch (RemoteException e) {
            }
            this.mCurToken = null;
        }
        this.mCurId = null;
        clearCurMethodLocked();
    }

    void resetCurrentMethodAndClient(int unbindClientReason) {
        this.mCurMethodId = null;
        unbindCurrentMethodLocked(false);
        unbindCurrentClientLocked(unbindClientReason);
    }

    void requestClientSessionLocked(ClientState cs) {
        if (!cs.sessionRequested) {
            Slog.v(TAG, "Creating new session for client " + cs);
            InputChannel[] channels = InputChannel.openInputChannelPair(cs.toString());
            cs.sessionRequested = true;
            executeOrSendMessage(this.mCurMethod, this.mCaller.obtainMessageOOO((int) MSG_CREATE_SESSION, this.mCurMethod, channels[1], new MethodCallback(this, this.mCurMethod, channels[0])));
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
                    updateSystemUiLocked(this.mCurToken, 0, this.mBackDisposition);
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
            for (ClientState cs : this.mClients.values()) {
                clearClientSessionLocked(cs);
            }
            finishSessionLocked(this.mEnabledSession);
            this.mEnabledSession = null;
            this.mCurMethod = null;
        }
        if (this.mStatusBar != null) {
            this.mStatusBar.setIconVisibility(this.mSlotIme, false);
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
                setInputMethodShownToSettings(this.mContext, this.mInputShown);
                unbindCurrentClientLocked(3);
            }
        }
    }

    public void updateStatusIcon(IBinder token, String packageName, int iconId) {
        synchronized (this.mMethodMap) {
            if (calledWithValidToken(token)) {
                long ident = Binder.clearCallingIdentity();
                if (iconId == 0) {
                    Slog.d(TAG, "hide the small icon for the input method");
                    if (this.mStatusBar != null) {
                        this.mStatusBar.setIconVisibility(this.mSlotIme, false);
                    }
                } else if (packageName != null) {
                    Slog.d(TAG, "show a small icon for the input method");
                    String str = null;
                    CharSequence contentDescription = null;
                    try {
                        PackageManager packageManager = this.mContext.getPackageManager();
                        contentDescription = packageManager.getApplicationLabel(this.mIPackageManager.getApplicationInfo(packageName, 0, this.mSettings.getCurrentUserId()));
                    } catch (RemoteException e) {
                    }
                    if (this.mStatusBar != null) {
                        StatusBarManagerService statusBarManagerService = this.mStatusBar;
                        String str2 = this.mSlotIme;
                        if (contentDescription != null) {
                            str = contentDescription.toString();
                        }
                        statusBarManagerService.setIcon(str2, packageName, iconId, 0, str);
                        this.mStatusBar.setIconVisibility(this.mSlotIme, true);
                    }
                }
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private boolean shouldShowImeSwitcherLocked(int visibility) {
        if (this.mShowOngoingImeSwitcherForPhones && this.mSwitchingDialog == null) {
            if ((this.mWindowManagerInternal.isKeyguardShowingAndNotOccluded() && this.mKeyguardManager != null && this.mKeyguardManager.isKeyguardSecure()) || (visibility & 1) == 0) {
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
            InputMethodSubtype auxSubtype = null;
            InputMethodSubtype auxSubtype2 = null;
            int nonAuxCount = 0;
            int nonAuxCount2 = 0;
            for (int nonAuxCount3 = 0; nonAuxCount3 < N; nonAuxCount3++) {
                InputMethodInfo imi = imis.get(nonAuxCount3);
                List<InputMethodSubtype> subtypes = this.mSettings.getEnabledInputMethodSubtypeListLocked(this.mContext, imi, true);
                int subtypeCount = subtypes.size();
                if (subtypeCount == 0) {
                    nonAuxCount2++;
                } else {
                    InputMethodSubtype auxSubtype3 = auxSubtype;
                    InputMethodSubtype nonAuxSubtype = auxSubtype2;
                    int auxCount = nonAuxCount;
                    int nonAuxCount4 = nonAuxCount2;
                    for (int nonAuxCount5 = 0; nonAuxCount5 < subtypeCount; nonAuxCount5++) {
                        InputMethodSubtype subtype = subtypes.get(nonAuxCount5);
                        if (!subtype.isAuxiliary()) {
                            nonAuxCount4++;
                            nonAuxSubtype = subtype;
                        } else {
                            auxCount++;
                            auxSubtype3 = subtype;
                        }
                    }
                    nonAuxCount2 = nonAuxCount4;
                    nonAuxCount = auxCount;
                    auxSubtype2 = nonAuxSubtype;
                    auxSubtype = auxSubtype3;
                }
            }
            if (nonAuxCount2 > 1 || nonAuxCount > 1) {
                return true;
            }
            if (nonAuxCount2 == 1 && nonAuxCount == 1) {
                return auxSubtype2 == null || auxSubtype == null || !((auxSubtype2.getLocale().equals(auxSubtype.getLocale()) || auxSubtype.overridesImplicitlyEnabledSubtype() || auxSubtype2.overridesImplicitlyEnabledSubtype()) && auxSubtype2.containsExtraValueKey(TAG_TRY_SUPPRESSING_IME_SWITCHER));
            }
            return false;
        }
        return false;
    }

    private boolean isKeyguardLocked() {
        return this.mKeyguardManager != null && this.mKeyguardManager.isKeyguardLocked();
    }

    public void setImeWindowStatus(IBinder token, IBinder startInputToken, int vis, int backDisposition) {
        StartInputInfo info;
        boolean dismissImeOnBackKeyPressed;
        if (!calledWithValidToken(token)) {
            return;
        }
        synchronized (this.mMethodMap) {
            info = this.mStartInputMap.get(startInputToken);
            this.mImeWindowVis = vis;
            this.mBackDisposition = backDisposition;
            updateSystemUiLocked(token, vis, backDisposition);
        }
        boolean z = false;
        switch (backDisposition) {
            case 1:
                dismissImeOnBackKeyPressed = false;
                break;
            case 2:
                dismissImeOnBackKeyPressed = true;
                break;
            default:
                if ((vis & 2) == 0) {
                    dismissImeOnBackKeyPressed = false;
                    break;
                } else {
                    dismissImeOnBackKeyPressed = true;
                    break;
                }
        }
        WindowManagerInternal windowManagerInternal = this.mWindowManagerInternal;
        if ((vis & 2) != 0) {
            z = true;
        }
        windowManagerInternal.updateInputMethodWindowStatus(token, z, dismissImeOnBackKeyPressed, info != null ? info.mTargetWindow : null);
    }

    private void updateSystemUi(IBinder token, int vis, int backDisposition) {
        synchronized (this.mMethodMap) {
            updateSystemUiLocked(token, vis, backDisposition);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateSystemUiLocked(IBinder token, int vis, int backDisposition) {
        if (!calledWithValidToken(token)) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        if (vis != 0) {
            try {
                if (isKeyguardLocked() && !this.mCurClientInKeyguard) {
                    vis = 0;
                }
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(ident);
                throw th;
            }
        }
        boolean needsToShowImeSwitcher = shouldShowImeSwitcherLocked(vis);
        if (this.mStatusBar != null) {
            this.mStatusBar.setImeWindowStatus(token, vis, backDisposition, needsToShowImeSwitcher);
        }
        InputMethodInfo imi = this.mMethodMap.get(this.mCurMethodId);
        if (imi != null && needsToShowImeSwitcher) {
            CharSequence title = this.mRes.getText(17040840);
            CharSequence summary = InputMethodUtils.getImeAndSubtypeDisplayName(this.mContext, imi, this.mCurrentSubtype);
            this.mImeSwitcherNotification.setContentTitle(title).setContentText(summary).setContentIntent(this.mImeSwitchPendingIntent);
            try {
                if (this.mNotificationManager != null && !this.mIWindowManager.hasNavigationBar()) {
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
        Binder.restoreCallingIdentity(ident);
    }

    public void registerSuggestionSpansForNotification(SuggestionSpan[] spans) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (this.mMethodMap) {
            InputMethodInfo currentImi = this.mMethodMap.get(this.mCurMethodId);
            for (SuggestionSpan ss : spans) {
                if (!TextUtils.isEmpty(ss.getNotificationTargetClassName())) {
                    this.mSecureSuggestionSpans.put(ss, currentImi);
                }
            }
        }
    }

    public boolean notifySuggestionPicked(SuggestionSpan span, String originalString, int index) {
        if (calledFromValidUser()) {
            synchronized (this.mMethodMap) {
                InputMethodInfo targetImi = this.mSecureSuggestionSpans.get(span);
                if (targetImi != null) {
                    String[] suggestions = span.getSuggestions();
                    if (index >= 0 && index < suggestions.length) {
                        String className = span.getNotificationTargetClassName();
                        Intent intent = new Intent();
                        intent.setClassName(targetImi.getPackageName(), className);
                        intent.setAction("android.text.style.SUGGESTION_PICKED");
                        intent.putExtra("before", originalString);
                        intent.putExtra("after", suggestions[index]);
                        intent.putExtra("hashcode", span.hashCode());
                        long ident = Binder.clearCallingIdentity();
                        this.mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
                        Binder.restoreCallingIdentity(ident);
                        return true;
                    }
                    return false;
                }
                return false;
            }
        }
        return false;
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
            this.mShortcutInputMethodsAndSubtypes.clear();
        } else {
            resetCurrentMethodAndClient(4);
        }
        this.mSwitchingController.resetCircularListLocked(this.mContext);
    }

    public void updateKeyboardFromSettingsLocked() {
        this.mShowImeWithHardKeyboard = this.mSettings.isShowImeWithHardKeyboardEnabled();
        if (this.mSwitchingDialog != null && this.mSwitchingDialogTitleView != null && this.mSwitchingDialog.isShowing()) {
            Switch hardKeySwitch = (Switch) this.mSwitchingDialogTitleView.findViewById(16909027);
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
                        updateSystemUiLocked(this.mCurToken, this.mImeWindowVis, this.mBackDisposition);
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
        if (calledFromValidUser()) {
            try {
                Slog.d(TAG, "showSoftInput() " + this.mContext.getPackageName() + " " + this.mIPackageManager.getNameForUid(Binder.getCallingUid()) + "  " + Binder.getCallingPid());
            } catch (Exception e) {
                Slog.v(TAG, "showSoftInput() " + e);
            }
            int uid = Binder.getCallingUid();
            long ident = Binder.clearCallingIdentity();
            try {
                synchronized (this.mMethodMap) {
                    if (this.mCurClient == null || client == null || this.mCurClient.client.asBinder() != client.asBinder()) {
                        try {
                            if (!this.mIWindowManager.inputMethodClientHasFocus(client)) {
                                Slog.w(TAG, "Ignoring showSoftInput of uid " + uid + ": " + client);
                                return false;
                            }
                        } catch (RemoteException e2) {
                            return false;
                        }
                    }
                    Slog.v(TAG, "Client requesting input be shown  " + flags);
                    return showCurrentInputLocked(flags, resultReceiver);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
        return false;
    }

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
                Slog.d(TAG, "showCurrentInputLocked: mCurToken=" + this.mCurToken);
                executeOrSendMessage(this.mCurMethod, this.mCaller.obtainMessageIOO((int) MSG_SHOW_SOFT_INPUT, getImeShowFlags(), this.mCurMethod, resultReceiver));
                this.mInputShown = true;
                setInputMethodShownToSettings(this.mContext, this.mInputShown);
                if (this.mHaveConnection && !this.mVisibleBound) {
                    Slog.d(TAG, "bindCurrentInputMethodServiceLocked mVisibleConnection");
                    bindCurrentInputMethodServiceLocked(this.mCurIntent, this.mVisibleConnection, IME_VISIBLE_BIND_FLAGS);
                    this.mVisibleBound = true;
                }
                return true;
            } else if (this.mHaveConnection && SystemClock.uptimeMillis() >= this.mLastBindTime + TIME_TO_RECONNECT) {
                EventLog.writeEvent((int) EventLogTags.IMF_FORCE_RECONNECT_IME, this.mCurMethodId, Long.valueOf(SystemClock.uptimeMillis() - this.mLastBindTime), 1);
                Slog.w(TAG, "Force disconnect/connect to the IME in showCurrentInputLocked()");
                this.mContext.unbindService(this);
                bindCurrentInputMethodServiceLocked(this.mCurIntent, this, IME_CONNECTION_BIND_FLAGS);
                return false;
            } else {
                Slog.d(TAG, "Can't show input: connection = " + this.mHaveConnection + ", time = " + ((this.mLastBindTime + TIME_TO_RECONNECT) - SystemClock.uptimeMillis()));
                return false;
            }
        }
        return false;
    }

    public boolean hideSoftInput(IInputMethodClient client, int flags, ResultReceiver resultReceiver) {
        if (calledFromValidUser()) {
            try {
                Slog.d(TAG, "hideSoftInput() " + this.mContext.getPackageName() + " " + this.mIPackageManager.getNameForUid(Binder.getCallingUid()) + "  " + Binder.getCallingPid());
            } catch (Exception e) {
                Slog.v(TAG, "hideSoftInput() " + e);
            }
            int uid = Binder.getCallingUid();
            long ident = Binder.clearCallingIdentity();
            try {
                synchronized (this.mMethodMap) {
                    if (this.mCurClient == null || client == null || this.mCurClient.client.asBinder() != client.asBinder()) {
                        try {
                            if (!this.mIWindowManager.inputMethodClientHasFocus(client)) {
                                Slog.w(TAG, "Ignoring hideSoftInput of uid " + uid + ": " + client);
                                return false;
                            }
                        } catch (RemoteException e2) {
                            return false;
                        }
                    }
                    Slog.v(TAG, "Client requesting input be hidden");
                    return hideCurrentInputLocked(flags, resultReceiver);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
        return false;
    }

    public boolean getInputShown() {
        return this.mInputShown;
    }

    public void forceHideSoftInputMethod() {
        Slog.d(TAG, "forceHideSoftInputMethod() " + this.mInputShown + " mHaveConnection:" + this.mHaveConnection + " mVisibleBound:" + this.mVisibleBound);
        if (this.mInputShown) {
            hideCurrentInputLocked(0, null);
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
                executeOrSendMessage(this.mCurMethod, this.mCaller.obtainMessageOO((int) MSG_HIDE_SOFT_INPUT, this.mCurMethod, resultReceiver));
                res = true;
            } else {
                res = false;
            }
            synchronized (this.mUnBindLock) {
                if (this.mHaveConnection && this.mVisibleBound) {
                    Slog.d(TAG, "hideCurrentInputLocked() unbindService");
                    this.mContext.unbindService(this.mVisibleConnection);
                    this.mVisibleBound = false;
                }
            }
            this.mInputShown = false;
            setInputMethodShownToSettings(this.mContext, this.mInputShown);
            this.mShowRequested = false;
            this.mShowExplicitlyRequested = false;
            this.mShowForced = false;
            return res;
        }
    }

    public InputBindResult startInputOrWindowGainedFocus(int startInputReason, IInputMethodClient client, IBinder windowToken, int controlFlags, int softInputMode, int windowFlags, EditorInfo attribute, IInputContext inputContext, int missingMethods, int unverifiedTargetSdkVersion) {
        InputBindResult result;
        if (windowToken != null) {
            result = windowGainedFocus(startInputReason, client, windowToken, controlFlags, softInputMode, windowFlags, attribute, inputContext, missingMethods, unverifiedTargetSdkVersion);
        } else {
            result = startInput(startInputReason, client, inputContext, missingMethods, attribute, controlFlags);
        }
        if (result == null) {
            Slog.wtf(TAG, "InputBindResult is @NonNull. startInputReason=" + InputMethodClient.getStartInputReason(startInputReason) + " windowFlags=#" + Integer.toHexString(windowFlags) + " editorInfo=" + attribute);
            return InputBindResult.NULL;
        }
        return result;
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Not initialized variable reg: 20, insn: 0x0254: MOVE  (r1 I:??[long, double]) = (r20 I:??[long, double] A[D('ident' long)]), block:B:83:0x0254 */
    /* JADX WARN: Removed duplicated region for block: B:104:0x02be  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private com.android.internal.view.InputBindResult windowGainedFocus(int r24, com.android.internal.view.IInputMethodClient r25, android.os.IBinder r26, int r27, int r28, int r29, android.view.inputmethod.EditorInfo r30, com.android.internal.view.IInputContext r31, int r32, int r33) {
        /*
            Method dump skipped, instructions count: 844
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.InputMethodManagerService.windowGainedFocus(int, com.android.internal.view.IInputMethodClient, android.os.IBinder, int, int, int, android.view.inputmethod.EditorInfo, com.android.internal.view.IInputContext, int, int):com.android.internal.view.InputBindResult");
    }

    private boolean canShowInputMethodPickerLocked(IInputMethodClient client) {
        int uid = Binder.getCallingUid();
        if (UserHandle.getAppId(uid) == 1000) {
            return true;
        }
        if (this.mCurFocusedWindowClient == null || client == null || this.mCurFocusedWindowClient.client.asBinder() != client.asBinder()) {
            return (this.mCurIntent != null && InputMethodUtils.checkIfPackageBelongsToUid(this.mAppOpsManager, uid, this.mCurIntent.getComponent().getPackageName())) || this.mContext.checkCallingPermission("android.permission.WRITE_SECURE_SETTINGS") == 0;
        }
        return true;
    }

    public void showInputMethodPickerFromClient(IInputMethodClient client, int auxiliarySubtypeMode) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (this.mMethodMap) {
            if (!canShowInputMethodPickerLocked(client)) {
                Slog.w(TAG, "Ignoring showInputMethodPickerFromClient of uid " + Binder.getCallingUid() + ": " + client);
                return;
            }
            this.mHandler.sendMessage(this.mCaller.obtainMessageI(1, auxiliarySubtypeMode));
        }
    }

    public boolean isInputMethodPickerShownForTest() {
        synchronized (this.mMethodMap) {
            if (this.mSwitchingDialog == null) {
                return false;
            }
            return this.mSwitchingDialog.isShowing();
        }
    }

    public void setInputMethod(IBinder token, String id) {
        if (!calledFromValidUser()) {
            return;
        }
        setInputMethodWithSubtypeId(token, id, -1);
    }

    public void setInputMethodAndSubtype(IBinder token, String id, InputMethodSubtype subtype) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (this.mMethodMap) {
            try {
                if (subtype != null) {
                    setInputMethodWithSubtypeIdLocked(token, id, InputMethodUtils.getSubtypeIdFromHashCode(this.mMethodMap.get(id), subtype.hashCode()));
                } else {
                    setInputMethod(token, id);
                }
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    public void showInputMethodAndSubtypeEnablerFromClient(IInputMethodClient client, String inputMethodId) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (this.mMethodMap) {
            executeOrSendMessage(this.mCurMethod, this.mCaller.obtainMessageO(2, inputMethodId));
        }
    }

    public boolean switchToPreviousInputMethod(IBinder token) {
        InputMethodInfo lastImi;
        int subtypeId;
        String targetLastImiId;
        List<InputMethodInfo> enabled;
        String locale;
        InputMethodSubtype keyboardSubtype;
        if (!calledFromValidUser()) {
            return false;
        }
        synchronized (this.mMethodMap) {
            try {
                try {
                    Pair<String, String> lastIme = this.mSettings.getLastInputMethodAndSubtypeLocked();
                    if (lastIme != null) {
                        lastImi = this.mMethodMap.get(lastIme.first);
                    } else {
                        lastImi = null;
                    }
                    String targetLastImiId2 = null;
                    int subtypeId2 = -1;
                    if (lastIme != null && lastImi != null) {
                        boolean imiIdIsSame = lastImi.getId().equals(this.mCurMethodId);
                        int lastSubtypeHash = Integer.parseInt((String) lastIme.second);
                        int currentSubtypeHash = this.mCurrentSubtype == null ? -1 : this.mCurrentSubtype.hashCode();
                        if (!imiIdIsSame || lastSubtypeHash != currentSubtypeHash) {
                            targetLastImiId2 = (String) lastIme.first;
                            subtypeId2 = InputMethodUtils.getSubtypeIdFromHashCode(lastImi, lastSubtypeHash);
                        }
                    }
                    boolean imiIdIsSame2 = TextUtils.isEmpty(targetLastImiId2);
                    if (imiIdIsSame2 && !InputMethodUtils.canAddToLastInputMethod(this.mCurrentSubtype) && (enabled = this.mSettings.getEnabledInputMethodListLocked()) != null) {
                        int N = enabled.size();
                        if (this.mCurrentSubtype == null) {
                            locale = this.mRes.getConfiguration().locale.toString();
                        } else {
                            locale = this.mCurrentSubtype.getLocale();
                        }
                        subtypeId = subtypeId2;
                        targetLastImiId = targetLastImiId2;
                        for (int i = 0; i < N; i++) {
                            InputMethodInfo imi = enabled.get(i);
                            if (imi.getSubtypeCount() > 0 && InputMethodUtils.isSystemIme(imi) && (keyboardSubtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(this.mRes, InputMethodUtils.getSubtypes(imi), "keyboard", locale, true)) != null) {
                                targetLastImiId = imi.getId();
                                subtypeId = InputMethodUtils.getSubtypeIdFromHashCode(imi, keyboardSubtype.hashCode());
                                if (keyboardSubtype.getLocale().equals(locale)) {
                                    break;
                                }
                            }
                        }
                    } else {
                        subtypeId = subtypeId2;
                        targetLastImiId = targetLastImiId2;
                    }
                    if (TextUtils.isEmpty(targetLastImiId)) {
                        return false;
                    }
                    Slog.d(TAG, "Switch to: " + lastImi.getId() + ", " + ((String) lastIme.second) + ", from: " + this.mCurMethodId + ", " + subtypeId);
                    setInputMethodWithSubtypeIdLocked(token, targetLastImiId, subtypeId);
                    return true;
                } catch (Throwable th) {
                    th = th;
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
            }
        }
    }

    public boolean switchToNextInputMethod(IBinder token, boolean onlyCurrentIme) {
        if (calledFromValidUser()) {
            synchronized (this.mMethodMap) {
                if (calledWithValidToken(token)) {
                    InputMethodSubtypeSwitchingController.ImeSubtypeListItem nextSubtype = this.mSwitchingController.getNextInputMethodLocked(onlyCurrentIme, this.mMethodMap.get(this.mCurMethodId), this.mCurrentSubtype, true);
                    if (nextSubtype == null) {
                        return false;
                    }
                    setInputMethodWithSubtypeIdLocked(token, nextSubtype.mImi.getId(), nextSubtype.mSubtypeId);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    public boolean shouldOfferSwitchingToNextInputMethod(IBinder token) {
        if (calledFromValidUser()) {
            synchronized (this.mMethodMap) {
                if (calledWithValidToken(token)) {
                    InputMethodSubtypeSwitchingController.ImeSubtypeListItem nextSubtype = this.mSwitchingController.getNextInputMethodLocked(false, this.mMethodMap.get(this.mCurMethodId), this.mCurrentSubtype, true);
                    return nextSubtype != null;
                }
                return false;
            }
        }
        return false;
    }

    public InputMethodSubtype getLastInputMethodSubtype() {
        if (calledFromValidUser()) {
            synchronized (this.mMethodMap) {
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
        }
        return null;
    }

    public void setAdditionalInputMethodSubtypes(String imiId, InputMethodSubtype[] subtypes) {
        if (!calledFromValidUser() || TextUtils.isEmpty(imiId) || subtypes == null) {
            return;
        }
        synchronized (this.mMethodMap) {
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
                                this.mFileManager.addInputMethodSubtypes(imi, subtypes);
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

    public int getInputMethodWindowVisibleHeight() {
        return this.mWindowManagerInternal.getInputMethodWindowVisibleHeight();
    }

    public void clearLastInputMethodWindowForTransition(IBinder token) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (this.mMethodMap) {
            if (calledWithValidToken(token)) {
                this.mWindowManagerInternal.clearLastInputMethodWindowForTransition();
            }
        }
    }

    public void notifyUserAction(int sequenceNumber) {
        Slog.d(TAG, "Got the notification of a user action. sequenceNumber:" + sequenceNumber);
        synchronized (this.mMethodMap) {
            if (this.mCurUserActionNotificationSequenceNumber != sequenceNumber) {
                Slog.d(TAG, "Ignoring the user action notification due to the sequence number mismatch. expected:" + this.mCurUserActionNotificationSequenceNumber + " actual: " + sequenceNumber);
                return;
            }
            InputMethodInfo imi = this.mMethodMap.get(this.mCurMethodId);
            if (imi != null) {
                this.mSwitchingController.onUserActionLocked(imi, this.mCurrentSubtype);
            }
        }
    }

    private void setInputMethodWithSubtypeId(IBinder token, String id, int subtypeId) {
        synchronized (this.mMethodMap) {
            setInputMethodWithSubtypeIdLocked(token, id, subtypeId);
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

    public void hideMySoftInput(IBinder token, int flags) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (this.mMethodMap) {
            if (calledWithValidToken(token)) {
                long ident = Binder.clearCallingIdentity();
                hideCurrentInputLocked(flags, null);
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public void showMySoftInput(IBinder token, int flags) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (this.mMethodMap) {
            if (calledWithValidToken(token)) {
                long ident = Binder.clearCallingIdentity();
                showCurrentInputLocked(flags, null);
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    void setEnabledSessionInMainThread(SessionState session) {
        if (this.mEnabledSession != session) {
            if (this.mEnabledSession != null && this.mEnabledSession.session != null) {
                try {
                    Slog.v(TAG, "Disabling: " + this.mEnabledSession);
                    this.mEnabledSession.method.setSessionEnabled(this.mEnabledSession.session, false);
                } catch (RemoteException e) {
                }
            }
            this.mEnabledSession = session;
            if (this.mEnabledSession != null && this.mEnabledSession.session != null) {
                try {
                    Slog.v(TAG, "Enabling: " + this.mEnabledSession);
                    this.mEnabledSession.method.setSessionEnabled(this.mEnabledSession.session, true);
                } catch (RemoteException e2) {
                }
            }
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:107:0x01d1, code lost:
        if (android.os.Binder.isProxy(r1) != false) goto L86;
     */
    /* JADX WARN: Code restructure failed: missing block: B:108:0x01d3, code lost:
        r3.dispose();
     */
    /* JADX WARN: Code restructure failed: missing block: B:66:0x0142, code lost:
        if (android.os.Binder.isProxy(r1) != false) goto L49;
     */
    /* JADX WARN: Code restructure failed: missing block: B:96:0x01ba, code lost:
        if (android.os.Binder.isProxy(r1) != false) goto L86;
     */
    @Override // android.os.Handler.Callback
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public boolean handleMessage(android.os.Message r13) {
        /*
            Method dump skipped, instructions count: 846
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.InputMethodManagerService.handleMessage(android.os.Message):boolean");
    }

    private void handleSetInteractive(boolean interactive) {
        synchronized (this.mMethodMap) {
            this.mIsInteractive = interactive;
            updateSystemUiLocked(this.mCurToken, interactive ? this.mImeWindowVis : 0, this.mBackDisposition);
            if (this.mCurClient != null && this.mCurClient.client != null) {
                executeOrSendMessage(this.mCurClient.client, this.mCaller.obtainMessageIIO((int) MSG_SET_ACTIVE, this.mIsInteractive ? 1 : 0, this.mInFullscreenMode ? 1 : 0, this.mCurClient));
            }
        }
    }

    private void handleSwitchInputMethod(boolean forwardDirection) {
        synchronized (this.mMethodMap) {
            InputMethodSubtypeSwitchingController.ImeSubtypeListItem nextSubtype = this.mSwitchingController.getNextInputMethodLocked(false, this.mMethodMap.get(this.mCurMethodId), this.mCurrentSubtype, forwardDirection);
            if (nextSubtype == null) {
                return;
            }
            setInputMethodLocked(nextSubtype.mImi.getId(), nextSubtype.mSubtypeId);
            InputMethodInfo newInputMethodInfo = this.mMethodMap.get(this.mCurMethodId);
            if (newInputMethodInfo == null) {
                return;
            }
            CharSequence toastText = InputMethodUtils.getImeAndSubtypeDisplayName(this.mContext, newInputMethodInfo, this.mCurrentSubtype);
            if (!TextUtils.isEmpty(toastText)) {
                if (this.mSubtypeSwitchedByShortCutToast == null) {
                    this.mSubtypeSwitchedByShortCutToast = Toast.makeText(this.mContext, toastText, 0);
                } else {
                    this.mSubtypeSwitchedByShortCutToast.setText(toastText);
                }
                this.mSubtypeSwitchedByShortCutToast.show();
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

    /* JADX INFO: Access modifiers changed from: private */
    public int getComponentMatchingFlags(int baseFlags) {
        synchronized (this.mMethodMap) {
            if (this.mBindInstantServiceAllowed) {
                baseFlags |= DumpState.DUMP_VOLUMES;
            }
        }
        return baseFlags;
    }

    @GuardedBy("mMethodMap")
    void buildInputMethodListLocked(boolean resetDefaultEnabledIme) {
        boolean resetDefaultEnabledIme2 = resetDefaultEnabledIme;
        Slog.d(TAG, "--- re-buildInputMethodList reset = " + resetDefaultEnabledIme2 + " \n ------ caller=" + Debug.getCallers(10));
        if (!this.mSystemReady) {
            Slog.e(TAG, "buildInputMethodListLocked is not allowed until system is ready");
            return;
        }
        this.mMethodList.clear();
        this.mMethodMap.clear();
        this.mMethodMapUpdateCount++;
        this.mMyPackageMonitor.clearKnownImePackageNamesLocked();
        PackageManager pm = this.mContext.getPackageManager();
        List<ResolveInfo> services = pm.queryIntentServicesAsUser(new Intent("android.view.InputMethod"), getComponentMatchingFlags(32896), this.mSettings.getCurrentUserId());
        HashMap<String, List<InputMethodSubtype>> additionalSubtypeMap = this.mFileManager.getAllAdditionalInputMethodSubtypes();
        int i = 0;
        while (true) {
            int i2 = i;
            int i3 = services.size();
            if (i2 >= i3) {
                break;
            }
            ResolveInfo ri = services.get(i2);
            ServiceInfo si = ri.serviceInfo;
            String imeId = InputMethodInfo.computeId(ri);
            if (!"android.permission.BIND_INPUT_METHOD".equals(si.permission)) {
                Slog.w(TAG, "Skipping input method " + imeId + ": it does not require the permission android.permission.BIND_INPUT_METHOD");
            } else {
                Slog.d(TAG, "Checking " + imeId);
                List<InputMethodSubtype> additionalSubtypes = additionalSubtypeMap.get(imeId);
                try {
                    InputMethodInfo p = new InputMethodInfo(this.mContext, ri, additionalSubtypes);
                    this.mMethodList.add(p);
                    String id = p.getId();
                    this.mMethodMap.put(id, p);
                    Slog.d(TAG, "Found an input method " + p);
                } catch (Exception e) {
                    Slog.wtf(TAG, "Unable to load input method " + imeId, e);
                }
            }
            i = i2 + 1;
        }
        List<ResolveInfo> allInputMethodServices = pm.queryIntentServicesAsUser(new Intent("android.view.InputMethod"), getComponentMatchingFlags(512), this.mSettings.getCurrentUserId());
        int N = allInputMethodServices.size();
        for (int i4 = 0; i4 < N; i4++) {
            ServiceInfo si2 = allInputMethodServices.get(i4).serviceInfo;
            if ("android.permission.BIND_INPUT_METHOD".equals(si2.permission)) {
                this.mMyPackageMonitor.addKnownImePackageNameLocked(si2.packageName);
            }
        }
        boolean reenableMinimumNonAuxSystemImes = false;
        if (!resetDefaultEnabledIme2) {
            boolean enabledNonAuxImeFound = false;
            List<InputMethodInfo> enabledImes = this.mSettings.getEnabledInputMethodListLocked();
            int N2 = enabledImes.size();
            boolean enabledImeFound = false;
            int i5 = 0;
            while (true) {
                if (i5 >= N2) {
                    break;
                }
                InputMethodInfo imi = enabledImes.get(i5);
                if (this.mMethodList.contains(imi)) {
                    enabledImeFound = true;
                    if (!imi.isAuxiliaryIme()) {
                        enabledNonAuxImeFound = true;
                        break;
                    }
                }
                i5++;
            }
            if (!enabledImeFound) {
                Slog.i(TAG, "All the enabled IMEs are gone. Reset default enabled IMEs.");
                resetDefaultEnabledIme2 = true;
                resetSelectedInputMethodAndSubtypeLocked(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            } else if (!enabledNonAuxImeFound) {
                Slog.i(TAG, "All the enabled non-Aux IMEs are gone. Do partial reset.");
                reenableMinimumNonAuxSystemImes = true;
            }
        }
        if (resetDefaultEnabledIme2 || reenableMinimumNonAuxSystemImes) {
            ArrayList<InputMethodInfo> defaultEnabledIme = InputMethodUtils.getDefaultEnabledImes(this.mContext, this.mMethodList, reenableMinimumNonAuxSystemImes);
            int N3 = defaultEnabledIme.size();
            int i6 = 0;
            while (true) {
                int i7 = i6;
                if (i7 >= N3) {
                    break;
                }
                InputMethodInfo imi2 = defaultEnabledIme.get(i7);
                Slog.d(TAG, "--- enable ime = " + imi2);
                setInputMethodEnabledLocked(imi2.getId(), true);
                i6 = i7 + 1;
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
        return this.mKeyguardManager != null && this.mKeyguardManager.isKeyguardLocked() && this.mKeyguardManager.isKeyguardSecure();
    }

    private void showInputMethodMenu(boolean showAuxSubtypes) {
        int i;
        int subtypeId;
        Slog.v(TAG, "Show switching menu. showAuxSubtypes=" + showAuxSubtypes);
        boolean isScreenLocked = isScreenLocked();
        String lastInputMethodId = this.mSettings.getSelectedInputMethod();
        int lastInputMethodSubtypeId = this.mSettings.getSelectedInputMethodSubtypeId(lastInputMethodId);
        Slog.v(TAG, "Current IME: " + lastInputMethodId);
        synchronized (this.mMethodMap) {
            try {
                try {
                    HashMap<InputMethodInfo, List<InputMethodSubtype>> immis = this.mSettings.getExplicitlyOrImplicitlyEnabledInputMethodsAndSubtypeListLocked(this.mContext);
                    if (immis != null && immis.size() != 0) {
                        hideInputMethodMenuLocked();
                        List<InputMethodSubtypeSwitchingController.ImeSubtypeListItem> imList = this.mSwitchingController.getSortedInputMethodAndSubtypeListLocked(showAuxSubtypes, isScreenLocked);
                        if (lastInputMethodSubtypeId == -1) {
                            try {
                                InputMethodSubtype currentSubtype = getCurrentInputMethodSubtypeLocked();
                                if (currentSubtype != null) {
                                    InputMethodInfo currentImi = this.mMethodMap.get(this.mCurMethodId);
                                    lastInputMethodSubtypeId = InputMethodUtils.getSubtypeIdFromHashCode(currentImi, currentSubtype.hashCode());
                                }
                            } catch (Throwable th) {
                                th = th;
                                throw th;
                            }
                        }
                        int N = imList.size();
                        this.mIms = new InputMethodInfo[N];
                        this.mSubtypeIds = new int[N];
                        int checkedItem = 0;
                        for (int checkedItem2 = 0; checkedItem2 < N; checkedItem2++) {
                            InputMethodSubtypeSwitchingController.ImeSubtypeListItem item = imList.get(checkedItem2);
                            this.mIms[checkedItem2] = item.mImi;
                            this.mSubtypeIds[checkedItem2] = item.mSubtypeId;
                            if (this.mIms[checkedItem2].getId().equals(lastInputMethodId) && ((subtypeId = this.mSubtypeIds[checkedItem2]) == -1 || ((lastInputMethodSubtypeId == -1 && subtypeId == 0) || subtypeId == lastInputMethodSubtypeId))) {
                                checkedItem = checkedItem2;
                            }
                        }
                        Context settingsContext = new ContextThemeWrapper((Context) ActivityThread.currentActivityThread().getSystemUiContext(), 16974371);
                        this.mDialogBuilder = new AlertDialog.Builder(settingsContext);
                        this.mDialogBuilder.setOnCancelListener(new DialogInterface.OnCancelListener() { // from class: com.android.server.InputMethodManagerService.4
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
                        View tv = inflater.inflate(17367158, (ViewGroup) null);
                        this.mDialogBuilder.setCustomTitle(tv);
                        this.mSwitchingDialogTitleView = tv;
                        View tv2 = this.mSwitchingDialogTitleView.findViewById(16909026);
                        if (this.mWindowManagerInternal.isHardKeyboardAvailable()) {
                            i = 0;
                        } else {
                            i = 8;
                        }
                        tv2.setVisibility(i);
                        Switch hardKeySwitch = (Switch) this.mSwitchingDialogTitleView.findViewById(16909027);
                        hardKeySwitch.setChecked(this.mShowImeWithHardKeyboard);
                        hardKeySwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.android.server.InputMethodManagerService.5
                            @Override // android.widget.CompoundButton.OnCheckedChangeListener
                            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                                InputMethodManagerService.this.mSettings.setShowImeWithHardKeyboard(isChecked);
                                InputMethodManagerService.this.hideInputMethodMenu();
                            }
                        });
                        final ImeSubtypeListAdapter adapter = new ImeSubtypeListAdapter(dialogContext, 17367159, imList, checkedItem);
                        DialogInterface.OnClickListener choiceListener = new DialogInterface.OnClickListener() { // from class: com.android.server.InputMethodManagerService.6
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
                        updateSystemUi(this.mCurToken, this.mImeWindowVis, this.mBackDisposition);
                        this.mSwitchingDialog.show();
                    }
                } catch (Throwable th2) {
                    th = th2;
                }
            } catch (Throwable th3) {
                th = th3;
            }
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
            RadioButton radioButton = (RadioButton) view.findViewById(16909309);
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
        if (this.mSwitchingDialog != null) {
            this.mSwitchingDialog.dismiss();
            this.mSwitchingDialog = null;
            this.mSwitchingDialogTitleView = null;
        }
        updateSystemUiLocked(this.mCurToken, this.mImeWindowVis, this.mBackDisposition);
        this.mDialogBuilder = null;
        this.mIms = null;
    }

    boolean setInputMethodEnabledLocked(String id, boolean enabled) {
        InputMethodInfo imm = this.mMethodMap.get(id);
        if (imm == null) {
            throw new IllegalArgumentException("Unknown id: " + this.mCurMethodId);
        }
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
                resetSelectedInputMethodAndSubtypeLocked(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            }
            return true;
        }
        return false;
    }

    private void setSelectedInputMethodAndSubtypeLocked(InputMethodInfo imi, int subtypeId, boolean setSubtypeOnly) {
        boolean isVrInput = imi != null && imi.isVrOnly();
        if (!isVrInput) {
            this.mSettings.saveCurrentInputMethodAndSubtypeToHistory(this.mCurMethodId, this.mCurrentSubtype);
        }
        this.mCurUserActionNotificationSequenceNumber = Math.max(this.mCurUserActionNotificationSequenceNumber + 1, 1);
        Slog.d(TAG, "Bump mCurUserActionNotificationSequenceNumber:" + this.mCurUserActionNotificationSequenceNumber);
        if (this.mCurClient != null && this.mCurClient.client != null) {
            executeOrSendMessage(this.mCurClient.client, this.mCaller.obtainMessageIO(3040, this.mCurUserActionNotificationSequenceNumber, this.mCurClient));
        }
        if (isVrInput) {
            return;
        }
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
            this.mSettings.putSelectedInputMethod(imi != null ? imi.getId() : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
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

    private Pair<InputMethodInfo, InputMethodSubtype> findLastResortApplicableShortcutInputMethodAndSubtypeLocked(String mode) {
        ArrayList<InputMethodSubtype> subtypesForSearch;
        List<InputMethodInfo> imis = this.mSettings.getEnabledInputMethodListLocked();
        InputMethodInfo mostApplicableIMI = null;
        InputMethodSubtype mostApplicableSubtype = null;
        boolean foundInSystemIME = false;
        Iterator<InputMethodInfo> it = imis.iterator();
        while (true) {
            if (!it.hasNext()) {
                break;
            }
            InputMethodInfo imi = it.next();
            String imiId = imi.getId();
            if (!foundInSystemIME || imiId.equals(this.mCurMethodId)) {
                InputMethodSubtype subtype = null;
                List<InputMethodSubtype> enabledSubtypes = this.mSettings.getEnabledInputMethodSubtypeListLocked(this.mContext, imi, true);
                if (this.mCurrentSubtype != null) {
                    subtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(this.mRes, enabledSubtypes, mode, this.mCurrentSubtype.getLocale(), false);
                }
                if (subtype == null) {
                    subtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(this.mRes, enabledSubtypes, mode, (String) null, true);
                }
                ArrayList<InputMethodSubtype> overridingImplicitlyEnabledSubtypes = InputMethodUtils.getOverridingImplicitlyEnabledSubtypes(imi, mode);
                if (overridingImplicitlyEnabledSubtypes.isEmpty()) {
                    subtypesForSearch = InputMethodUtils.getSubtypes(imi);
                } else {
                    subtypesForSearch = overridingImplicitlyEnabledSubtypes;
                }
                if (subtype == null && this.mCurrentSubtype != null) {
                    subtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(this.mRes, subtypesForSearch, mode, this.mCurrentSubtype.getLocale(), false);
                }
                if (subtype == null) {
                    subtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(this.mRes, subtypesForSearch, mode, (String) null, true);
                }
                if (subtype == null) {
                    continue;
                } else if (imiId.equals(this.mCurMethodId)) {
                    mostApplicableIMI = imi;
                    mostApplicableSubtype = subtype;
                    break;
                } else if (!foundInSystemIME) {
                    mostApplicableIMI = imi;
                    mostApplicableSubtype = subtype;
                    if ((imi.getServiceInfo().applicationInfo.flags & 1) != 0) {
                        foundInSystemIME = true;
                    }
                }
            }
        }
        if (mostApplicableIMI != null) {
            Slog.w(TAG, "Most applicable shortcut input method was:" + mostApplicableIMI.getId());
            if (mostApplicableSubtype != null) {
                Slog.w(TAG, "Most applicable shortcut input method subtype was:," + mostApplicableSubtype.getMode() + "," + mostApplicableSubtype.getLocale());
            }
        }
        if (mostApplicableIMI != null) {
            return new Pair<>(mostApplicableIMI, mostApplicableSubtype);
        }
        return null;
    }

    public InputMethodSubtype getCurrentInputMethodSubtype() {
        InputMethodSubtype currentInputMethodSubtypeLocked;
        if (!calledFromValidUser()) {
            return null;
        }
        synchronized (this.mMethodMap) {
            currentInputMethodSubtypeLocked = getCurrentInputMethodSubtypeLocked();
        }
        return currentInputMethodSubtypeLocked;
    }

    private InputMethodSubtype getCurrentInputMethodSubtypeLocked() {
        if (this.mCurMethodId == null) {
            return null;
        }
        boolean subtypeIsSelected = this.mSettings.isSubtypeSelected();
        InputMethodInfo imi = this.mMethodMap.get(this.mCurMethodId);
        if (imi == null || imi.getSubtypeCount() == 0) {
            return null;
        }
        if (!subtypeIsSelected || this.mCurrentSubtype == null || !InputMethodUtils.isValidSubtypeId(imi, this.mCurrentSubtype.hashCode())) {
            int subtypeId = this.mSettings.getSelectedInputMethodSubtypeId(this.mCurMethodId);
            if (subtypeId == -1) {
                List<InputMethodSubtype> explicitlyOrImplicitlyEnabledSubtypes = this.mSettings.getEnabledInputMethodSubtypeListLocked(this.mContext, imi, true);
                if (explicitlyOrImplicitlyEnabledSubtypes.size() != 1) {
                    if (explicitlyOrImplicitlyEnabledSubtypes.size() > 1) {
                        this.mCurrentSubtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(this.mRes, explicitlyOrImplicitlyEnabledSubtypes, "keyboard", (String) null, true);
                        if (this.mCurrentSubtype == null) {
                            this.mCurrentSubtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(this.mRes, explicitlyOrImplicitlyEnabledSubtypes, (String) null, (String) null, true);
                        }
                    }
                } else {
                    this.mCurrentSubtype = explicitlyOrImplicitlyEnabledSubtypes.get(0);
                }
            } else {
                this.mCurrentSubtype = (InputMethodSubtype) InputMethodUtils.getSubtypes(imi).get(subtypeId);
            }
        }
        return this.mCurrentSubtype;
    }

    public List getShortcutInputMethodsAndSubtypes() {
        synchronized (this.mMethodMap) {
            ArrayList<Object> ret = new ArrayList<>();
            if (this.mShortcutInputMethodsAndSubtypes.size() == 0) {
                Pair<InputMethodInfo, InputMethodSubtype> info = findLastResortApplicableShortcutInputMethodAndSubtypeLocked("voice");
                if (info != null) {
                    ret.add(info.first);
                    ret.add(info.second);
                }
                return ret;
            }
            for (InputMethodInfo imi : this.mShortcutInputMethodsAndSubtypes.keySet()) {
                ret.add(imi);
                Iterator<InputMethodSubtype> it = this.mShortcutInputMethodsAndSubtypes.get(imi).iterator();
                while (it.hasNext()) {
                    InputMethodSubtype subtype = it.next();
                    ret.add(subtype);
                }
            }
            return ret;
        }
    }

    public boolean setCurrentInputMethodSubtype(InputMethodSubtype subtype) {
        if (calledFromValidUser()) {
            synchronized (this.mMethodMap) {
                if (subtype != null) {
                    try {
                        if (this.mCurMethodId != null) {
                            InputMethodInfo imi = this.mMethodMap.get(this.mCurMethodId);
                            int subtypeId = InputMethodUtils.getSubtypeIdFromHashCode(imi, subtype.hashCode());
                            if (subtypeId != -1) {
                                setInputMethodLocked(this.mCurMethodId, subtypeId);
                                return true;
                            }
                        }
                    } catch (Throwable th) {
                        throw th;
                    }
                }
                return false;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class InputMethodFileManager {
        private static final String ADDITIONAL_SUBTYPES_FILE_NAME = "subtypes.xml";
        private static final String ATTR_ICON = "icon";
        private static final String ATTR_ID = "id";
        private static final String ATTR_IME_SUBTYPE_EXTRA_VALUE = "imeSubtypeExtraValue";
        private static final String ATTR_IME_SUBTYPE_ID = "subtypeId";
        private static final String ATTR_IME_SUBTYPE_LANGUAGE_TAG = "languageTag";
        private static final String ATTR_IME_SUBTYPE_LOCALE = "imeSubtypeLocale";
        private static final String ATTR_IME_SUBTYPE_MODE = "imeSubtypeMode";
        private static final String ATTR_IS_ASCII_CAPABLE = "isAsciiCapable";
        private static final String ATTR_IS_AUXILIARY = "isAuxiliary";
        private static final String ATTR_LABEL = "label";
        private static final String INPUT_METHOD_PATH = "inputmethod";
        private static final String NODE_IMI = "imi";
        private static final String NODE_SUBTYPE = "subtype";
        private static final String NODE_SUBTYPES = "subtypes";
        private static final String SYSTEM_PATH = "system";
        private final AtomicFile mAdditionalInputMethodSubtypeFile;
        private final HashMap<String, List<InputMethodSubtype>> mAdditionalSubtypesMap = new HashMap<>();
        private final HashMap<String, InputMethodInfo> mMethodMap;

        public InputMethodFileManager(HashMap<String, InputMethodInfo> methodMap, int userId) {
            File systemDir;
            if (methodMap == null) {
                throw new NullPointerException("methodMap is null");
            }
            this.mMethodMap = methodMap;
            if (userId == 0) {
                systemDir = new File(Environment.getDataDirectory(), SYSTEM_PATH);
            } else {
                systemDir = Environment.getUserSystemDirectory(userId);
            }
            File inputMethodDir = new File(systemDir, INPUT_METHOD_PATH);
            if (!inputMethodDir.exists() && !inputMethodDir.mkdirs()) {
                Slog.w(InputMethodManagerService.TAG, "Couldn't create dir.: " + inputMethodDir.getAbsolutePath());
            }
            File subtypeFile = new File(inputMethodDir, ADDITIONAL_SUBTYPES_FILE_NAME);
            this.mAdditionalInputMethodSubtypeFile = new AtomicFile(subtypeFile, "input-subtypes");
            if (!subtypeFile.exists()) {
                writeAdditionalInputMethodSubtypes(this.mAdditionalSubtypesMap, this.mAdditionalInputMethodSubtypeFile, methodMap);
            } else {
                readAdditionalInputMethodSubtypes(this.mAdditionalSubtypesMap, this.mAdditionalInputMethodSubtypeFile);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void deleteAllInputMethodSubtypes(String imiId) {
            synchronized (this.mMethodMap) {
                this.mAdditionalSubtypesMap.remove(imiId);
                writeAdditionalInputMethodSubtypes(this.mAdditionalSubtypesMap, this.mAdditionalInputMethodSubtypeFile, this.mMethodMap);
            }
        }

        public void addInputMethodSubtypes(InputMethodInfo imi, InputMethodSubtype[] additionalSubtypes) {
            synchronized (this.mMethodMap) {
                ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
                for (InputMethodSubtype subtype : additionalSubtypes) {
                    if (!subtypes.contains(subtype)) {
                        subtypes.add(subtype);
                    } else {
                        Slog.w(InputMethodManagerService.TAG, "Duplicated subtype definition found: " + subtype.getLocale() + ", " + subtype.getMode());
                    }
                }
                this.mAdditionalSubtypesMap.put(imi.getId(), subtypes);
                writeAdditionalInputMethodSubtypes(this.mAdditionalSubtypesMap, this.mAdditionalInputMethodSubtypeFile, this.mMethodMap);
            }
        }

        public HashMap<String, List<InputMethodSubtype>> getAllAdditionalInputMethodSubtypes() {
            HashMap<String, List<InputMethodSubtype>> hashMap;
            synchronized (this.mMethodMap) {
                hashMap = this.mAdditionalSubtypesMap;
            }
            return hashMap;
        }

        private static void writeAdditionalInputMethodSubtypes(HashMap<String, List<InputMethodSubtype>> allSubtypes, AtomicFile subtypesFile, HashMap<String, InputMethodInfo> methodMap) {
            boolean isSetMethodMap = methodMap != null && methodMap.size() > 0;
            FileOutputStream fos = null;
            try {
                fos = subtypesFile.startWrite();
                FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
                fastXmlSerializer.setOutput(fos, StandardCharsets.UTF_8.name());
                fastXmlSerializer.startDocument(null, true);
                fastXmlSerializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
                fastXmlSerializer.startTag(null, NODE_SUBTYPES);
                for (String imiId : allSubtypes.keySet()) {
                    if (!isSetMethodMap || methodMap.containsKey(imiId)) {
                        fastXmlSerializer.startTag(null, NODE_IMI);
                        fastXmlSerializer.attribute(null, ATTR_ID, imiId);
                        List<InputMethodSubtype> subtypesList = allSubtypes.get(imiId);
                        int N = subtypesList.size();
                        for (int i = 0; i < N; i++) {
                            InputMethodSubtype subtype = subtypesList.get(i);
                            fastXmlSerializer.startTag(null, NODE_SUBTYPE);
                            if (subtype.hasSubtypeId()) {
                                fastXmlSerializer.attribute(null, ATTR_IME_SUBTYPE_ID, String.valueOf(subtype.getSubtypeId()));
                            }
                            fastXmlSerializer.attribute(null, ATTR_ICON, String.valueOf(subtype.getIconResId()));
                            fastXmlSerializer.attribute(null, ATTR_LABEL, String.valueOf(subtype.getNameResId()));
                            fastXmlSerializer.attribute(null, ATTR_IME_SUBTYPE_LOCALE, subtype.getLocale());
                            fastXmlSerializer.attribute(null, ATTR_IME_SUBTYPE_LANGUAGE_TAG, subtype.getLanguageTag());
                            fastXmlSerializer.attribute(null, ATTR_IME_SUBTYPE_MODE, subtype.getMode());
                            fastXmlSerializer.attribute(null, ATTR_IME_SUBTYPE_EXTRA_VALUE, subtype.getExtraValue());
                            fastXmlSerializer.attribute(null, ATTR_IS_AUXILIARY, String.valueOf(subtype.isAuxiliary() ? 1 : 0));
                            fastXmlSerializer.attribute(null, ATTR_IS_ASCII_CAPABLE, String.valueOf(subtype.isAsciiCapable() ? 1 : 0));
                            fastXmlSerializer.endTag(null, NODE_SUBTYPE);
                        }
                        fastXmlSerializer.endTag(null, NODE_IMI);
                    } else {
                        Slog.w(InputMethodManagerService.TAG, "IME uninstalled or not valid.: " + imiId);
                    }
                }
                fastXmlSerializer.endTag(null, NODE_SUBTYPES);
                fastXmlSerializer.endDocument();
                subtypesFile.finishWrite(fos);
            } catch (IOException e) {
                Slog.w(InputMethodManagerService.TAG, "Error writing subtypes", e);
                if (fos != null) {
                    subtypesFile.failWrite(fos);
                }
            }
        }

        /* JADX WARN: Multi-variable type inference failed */
        /* JADX WARN: Type inference failed for: r1v3, types: [java.util.HashMap] */
        /* JADX WARN: Type inference failed for: r1v5 */
        private static void readAdditionalInputMethodSubtypes(HashMap<String, List<InputMethodSubtype>> allSubtypes, AtomicFile subtypesFile) {
            int i;
            int i2;
            String firstNodeName;
            int depth;
            String firstNodeName2;
            int depth2;
            HashMap<String, List<InputMethodSubtype>> hashMap = allSubtypes;
            if (hashMap == null || subtypesFile == null) {
                return;
            }
            allSubtypes.clear();
            try {
                FileInputStream fis = subtypesFile.openRead();
                String str = null;
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(fis, StandardCharsets.UTF_8.name());
                parser.getEventType();
                while (true) {
                    int type = parser.next();
                    i = 1;
                    i2 = 2;
                    if (type == 2 || type == 1) {
                        break;
                    }
                }
                String firstNodeName3 = parser.getName();
                if (!NODE_SUBTYPES.equals(firstNodeName3)) {
                    throw new XmlPullParserException("Xml doesn't start with subtypes");
                }
                int depth3 = parser.getDepth();
                String currentImiId = null;
                ArrayList<InputMethodSubtype> tempSubtypesArray = null;
                while (true) {
                    int type2 = parser.next();
                    if ((type2 != 3 || parser.getDepth() > depth3) && type2 != i) {
                        if (type2 != i2) {
                            firstNodeName = firstNodeName3;
                            depth = depth3;
                        } else {
                            String nodeName = parser.getName();
                            if (NODE_IMI.equals(nodeName)) {
                                currentImiId = parser.getAttributeValue(str, ATTR_ID);
                                if (TextUtils.isEmpty(currentImiId)) {
                                    Slog.w(InputMethodManagerService.TAG, "Invalid imi id found in subtypes.xml");
                                } else {
                                    tempSubtypesArray = new ArrayList<>();
                                    hashMap.put(currentImiId, tempSubtypesArray);
                                    firstNodeName2 = firstNodeName3;
                                    depth2 = depth3;
                                }
                            } else if (NODE_SUBTYPE.equals(nodeName)) {
                                if (TextUtils.isEmpty(currentImiId)) {
                                    firstNodeName = firstNodeName3;
                                    depth = depth3;
                                } else if (tempSubtypesArray == null) {
                                    firstNodeName = firstNodeName3;
                                    depth = depth3;
                                } else {
                                    int icon = Integer.parseInt(parser.getAttributeValue(str, ATTR_ICON));
                                    int label = Integer.parseInt(parser.getAttributeValue(str, ATTR_LABEL));
                                    String imeSubtypeLocale = parser.getAttributeValue(str, ATTR_IME_SUBTYPE_LOCALE);
                                    String languageTag = parser.getAttributeValue(str, ATTR_IME_SUBTYPE_LANGUAGE_TAG);
                                    String imeSubtypeMode = parser.getAttributeValue(str, ATTR_IME_SUBTYPE_MODE);
                                    String imeSubtypeExtraValue = parser.getAttributeValue(null, ATTR_IME_SUBTYPE_EXTRA_VALUE);
                                    firstNodeName2 = firstNodeName3;
                                    boolean isAuxiliary = "1".equals(String.valueOf(parser.getAttributeValue(null, ATTR_IS_AUXILIARY)));
                                    depth2 = depth3;
                                    boolean isAsciiCapable = "1".equals(String.valueOf(parser.getAttributeValue(null, ATTR_IS_ASCII_CAPABLE)));
                                    InputMethodSubtype.InputMethodSubtypeBuilder builder = new InputMethodSubtype.InputMethodSubtypeBuilder().setSubtypeNameResId(label).setSubtypeIconResId(icon).setSubtypeLocale(imeSubtypeLocale).setLanguageTag(languageTag).setSubtypeMode(imeSubtypeMode).setSubtypeExtraValue(imeSubtypeExtraValue).setIsAuxiliary(isAuxiliary).setIsAsciiCapable(isAsciiCapable);
                                    String subtypeIdString = parser.getAttributeValue(null, ATTR_IME_SUBTYPE_ID);
                                    if (subtypeIdString != null) {
                                        builder.setSubtypeId(Integer.parseInt(subtypeIdString));
                                    }
                                    tempSubtypesArray.add(builder.build());
                                }
                                Slog.w(InputMethodManagerService.TAG, "IME uninstalled or not valid.: " + currentImiId);
                            } else {
                                firstNodeName2 = firstNodeName3;
                                depth2 = depth3;
                            }
                            firstNodeName3 = firstNodeName2;
                            depth3 = depth2;
                            hashMap = allSubtypes;
                            str = null;
                            i = 1;
                            i2 = 2;
                        }
                        firstNodeName3 = firstNodeName;
                        depth3 = depth;
                        hashMap = allSubtypes;
                        str = null;
                        i = 1;
                        i2 = 2;
                    }
                }
                if (fis != null) {
                    fis.close();
                }
            } catch (IOException | NumberFormatException | XmlPullParserException e) {
                Slog.w(InputMethodManagerService.TAG, "Error reading subtypes", e);
            }
        }
    }

    /* loaded from: classes.dex */
    private static final class LocalServiceImpl implements InputMethodManagerInternal {
        private final Handler mHandler;

        LocalServiceImpl(Handler handler) {
            this.mHandler = handler;
        }

        public void setInteractive(boolean interactive) {
            this.mHandler.sendMessage(this.mHandler.obtainMessage(InputMethodManagerService.MSG_SET_INTERACTIVE, interactive ? 1 : 0, 0));
        }

        public void switchInputMethod(boolean forwardDirection) {
            this.mHandler.sendMessage(this.mHandler.obtainMessage(3050, forwardDirection ? 1 : 0, 0));
        }

        public void hideCurrentInputMethod() {
            this.mHandler.removeMessages(InputMethodManagerService.MSG_HIDE_CURRENT_INPUT_METHOD);
            this.mHandler.sendEmptyMessage(InputMethodManagerService.MSG_HIDE_CURRENT_INPUT_METHOD);
        }

        public void startVrInputMethodNoCheck(ComponentName componentName) {
            this.mHandler.sendMessage(this.mHandler.obtainMessage(InputMethodManagerService.MSG_START_VR_INPUT, componentName));
        }
    }

    private static String imeWindowStatusToString(int imeWindowVis) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        if ((imeWindowVis & 1) != 0) {
            sb.append("Active");
            first = false;
        }
        if ((imeWindowVis & 2) != 0) {
            if (!first) {
                sb.append("|");
            }
            sb.append("Visible");
        }
        return sb.toString();
    }

    public IInputContentUriToken createInputContentUriToken(IBinder token, Uri contentUri, String packageName) {
        if (calledFromValidUser()) {
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
            if (!"content".equals(contentUriScheme)) {
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
        return null;
    }

    public void reportFullscreenMode(IBinder token, boolean fullscreen) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized (this.mMethodMap) {
            if (calledWithValidToken(token)) {
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
                p.println("  Input Methods: mMethodMapUpdateCount=" + this.mMethodMapUpdateCount + " mBindInstantServiceAllowed=" + this.mBindInstantServiceAllowed);
                for (int i = 0; i < N; i++) {
                    InputMethodInfo info = this.mMethodList.get(i);
                    p.println("  InputMethod #" + i + ":");
                    info.dump(p, "    ");
                }
                p.println("  Clients:");
                for (ClientState ci : this.mClients.values()) {
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
                p.println("  mCurFocusedWindow=" + this.mCurFocusedWindow + " softInputMode=" + InputMethodClient.softInputModeToString(this.mCurFocusedWindowSoftInputMode) + " client=" + this.mCurFocusedWindowClient);
                focusedWindowClient = this.mCurFocusedWindowClient;
                p.println("  mCurId=" + this.mCurId + " mHaveConnect=" + this.mHaveConnection + " mBoundToMethod=" + this.mBoundToMethod);
                StringBuilder sb2 = new StringBuilder();
                sb2.append("  mCurToken=");
                sb2.append(this.mCurToken);
                p.println(sb2.toString());
                p.println("  mCurIntent=" + this.mCurIntent);
                method = this.mCurMethod;
                p.println("  mCurMethod=" + this.mCurMethod);
                p.println("  mEnabledSession=" + this.mEnabledSession);
                p.println("  mImeWindowVis=" + imeWindowStatusToString(this.mImeWindowVis));
                p.println("  mShowRequested=" + this.mShowRequested + " mShowExplicitlyRequested=" + this.mShowExplicitlyRequested + " mShowForced=" + this.mShowForced + " mInputShown=" + this.mInputShown);
                StringBuilder sb3 = new StringBuilder();
                sb3.append("  mInFullscreenMode=");
                sb3.append(this.mInFullscreenMode);
                p.println(sb3.toString());
                StringBuilder sb4 = new StringBuilder();
                sb4.append("  mCurUserActionNotificationSequenceNumber=");
                sb4.append(this.mCurUserActionNotificationSequenceNumber);
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
        new ShellCommandImpl(this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    /* loaded from: classes.dex */
    private static final class ShellCommandImpl extends ShellCommand {
        final InputMethodManagerService mService;

        ShellCommandImpl(InputMethodManagerService service) {
            this.mService = service;
        }

        /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
        public int onCommand(String cmd) {
            char c;
            if ("refresh_debug_properties".equals(cmd)) {
                return refreshDebugProperties();
            }
            if ("set-bind-instant-service-allowed".equals(cmd)) {
                return setBindInstantServiceAllowed();
            }
            if ("ime".equals(cmd)) {
                String imeCommand = getNextArg();
                if (imeCommand != null && !"help".equals(imeCommand) && !"-h".equals(imeCommand)) {
                    switch (imeCommand.hashCode()) {
                        case -1298848381:
                            if (imeCommand.equals(xpInputManagerService.InputPolicyKey.KEY_ENABLE)) {
                                c = 1;
                                break;
                            }
                            c = 65535;
                            break;
                        case 113762:
                            if (imeCommand.equals("set")) {
                                c = 3;
                                break;
                            }
                            c = 65535;
                            break;
                        case 3322014:
                            if (imeCommand.equals("list")) {
                                c = 0;
                                break;
                            }
                            c = 65535;
                            break;
                        case 108404047:
                            if (imeCommand.equals("reset")) {
                                c = 4;
                                break;
                            }
                            c = 65535;
                            break;
                        case 1671308008:
                            if (imeCommand.equals("disable")) {
                                c = 2;
                                break;
                            }
                            c = 65535;
                            break;
                        default:
                            c = 65535;
                            break;
                    }
                    switch (c) {
                        case 0:
                            return this.mService.handleShellCommandListInputMethods(this);
                        case 1:
                            return this.mService.handleShellCommandEnableDisableInputMethod(this, true);
                        case 2:
                            return this.mService.handleShellCommandEnableDisableInputMethod(this, false);
                        case 3:
                            return this.mService.handleShellCommandSetInputMethod(this);
                        case 4:
                            return this.mService.handleShellCommandResetInputMethod(this);
                        default:
                            getOutPrintWriter().println("Unknown command: " + imeCommand);
                            return -1;
                    }
                }
                onImeCommandHelp();
                return 0;
            }
            return handleDefaultCommands(cmd);
        }

        private int setBindInstantServiceAllowed() {
            return this.mService.handleSetBindInstantServiceAllowed(this);
        }

        private int refreshDebugProperties() {
            DebugFlags.FLAG_OPTIMIZE_START_INPUT.refresh();
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
                pw.println("  set-bind-instant-service-allowed true|false ");
                pw.println("    Set whether binding to services provided by instant apps is allowed.");
                if (pw != null) {
                    $closeResource(null, pw);
                }
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
                pw.println("enable <ID>");
                pw.increaseIndent();
                pw.println("allows the given input method ID to be used.");
                pw.decreaseIndent();
                pw.println("disable <ID>");
                pw.increaseIndent();
                pw.println("disallows the given input method ID to be used.");
                pw.decreaseIndent();
                pw.println("set <ID>");
                pw.increaseIndent();
                pw.println("switches to the given input method ID.");
                pw.decreaseIndent();
                pw.println("reset");
                pw.increaseIndent();
                pw.println("reset currently selected/enabled IMEs to the default ones as if the device is initially booted with the current locale.");
                pw.decreaseIndent();
                pw.decreaseIndent();
                $closeResource(null, pw);
            } finally {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int handleSetBindInstantServiceAllowed(ShellCommand shellCommand) {
        String allowedString = shellCommand.getNextArgRequired();
        if (allowedString == null) {
            shellCommand.getErrPrintWriter().println("Error: no true/false specified");
            return -1;
        }
        boolean allowed = Boolean.parseBoolean(allowedString);
        synchronized (this.mMethodMap) {
            if (this.mContext.checkCallingOrSelfPermission("android.permission.MANAGE_BIND_INSTANT_SERVICE") != 0) {
                shellCommand.getErrPrintWriter().print("Caller must have MANAGE_BIND_INSTANT_SERVICE permission");
                return -1;
            } else if (this.mBindInstantServiceAllowed == allowed) {
                return 0;
            } else {
                this.mBindInstantServiceAllowed = allowed;
                long ident = Binder.clearCallingIdentity();
                resetSelectedInputMethodAndSubtypeLocked(null);
                this.mSettings.putSelectedInputMethod((String) null);
                buildInputMethodListLocked(false);
                updateInputMethodsFromSettingsLocked(true);
                Binder.restoreCallingIdentity(ident);
                return 0;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int handleShellCommandListInputMethods(ShellCommand shellCommand) {
        boolean all = false;
        boolean brief = false;
        while (true) {
            String nextOption = shellCommand.getNextOption();
            if (nextOption != null) {
                char c = 65535;
                int hashCode = nextOption.hashCode();
                if (hashCode != 1492) {
                    if (hashCode == 1510 && nextOption.equals("-s")) {
                        c = 1;
                    }
                } else if (nextOption.equals("-a")) {
                    c = 0;
                }
                switch (c) {
                    case 0:
                        all = true;
                        break;
                    case 1:
                        brief = true;
                        break;
                }
            } else {
                List<InputMethodInfo> methods = all ? getInputMethodList() : getEnabledInputMethodList();
                final PrintWriter pr = shellCommand.getOutPrintWriter();
                Printer printer = new Printer() { // from class: com.android.server.-$$Lambda$InputMethodManagerService$87vt08aKi27xQgvHZ-wOyJeb5jo
                    @Override // android.util.Printer
                    public final void println(String str) {
                        pr.println(str);
                    }
                };
                int N = methods.size();
                for (int i = 0; i < N; i++) {
                    if (brief) {
                        pr.println(methods.get(i).getId());
                    } else {
                        pr.print(methods.get(i).getId());
                        pr.println(":");
                        methods.get(i).dump(printer, "  ");
                    }
                }
                return 0;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int handleShellCommandEnableDisableInputMethod(ShellCommand shellCommand, boolean enabled) {
        boolean previouslyEnabled;
        if (!calledFromValidUser()) {
            shellCommand.getErrPrintWriter().print("Must be called from the foreground user or with INTERACT_ACROSS_USERS_FULL");
            return -1;
        }
        String id = shellCommand.getNextArgRequired();
        synchronized (this.mMethodMap) {
            if (this.mContext.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != 0) {
                shellCommand.getErrPrintWriter().print("Caller must have WRITE_SECURE_SETTINGS permission");
                throw new SecurityException("Requires permission android.permission.WRITE_SECURE_SETTINGS");
            }
            long ident = Binder.clearCallingIdentity();
            previouslyEnabled = setInputMethodEnabledLocked(id, enabled);
            Binder.restoreCallingIdentity(ident);
        }
        PrintWriter pr = shellCommand.getOutPrintWriter();
        pr.print("Input method ");
        pr.print(id);
        pr.print(": ");
        pr.print(enabled == previouslyEnabled ? "already " : "now ");
        pr.println(enabled ? "enabled" : "disabled");
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int handleShellCommandSetInputMethod(ShellCommand shellCommand) {
        String id = shellCommand.getNextArgRequired();
        setInputMethod(null, id);
        PrintWriter pr = shellCommand.getOutPrintWriter();
        pr.print("Input method ");
        pr.print(id);
        pr.println("  selected");
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int handleShellCommandResetInputMethod(ShellCommand shellCommand) {
        String nextIme;
        List<InputMethodInfo> nextEnabledImes;
        if (!calledFromValidUser()) {
            shellCommand.getErrPrintWriter().print("Must be called from the foreground user or with INTERACT_ACROSS_USERS_FULL");
            return -1;
        }
        synchronized (this.mMethodMap) {
            if (this.mContext.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != 0) {
                shellCommand.getErrPrintWriter().print("Caller must have WRITE_SECURE_SETTINGS permission");
                throw new SecurityException("Requires permission android.permission.WRITE_SECURE_SETTINGS");
            }
            long ident = Binder.clearCallingIdentity();
            synchronized (this.mMethodMap) {
                hideCurrentInputLocked(0, null);
                unbindCurrentMethodLocked(false);
                resetSelectedInputMethodAndSubtypeLocked(null);
                this.mSettings.putSelectedInputMethod((String) null);
                ArrayList<InputMethodInfo> enabledImes = this.mSettings.getEnabledInputMethodListLocked();
                int N = enabledImes.size();
                for (int i = 0; i < N; i++) {
                    setInputMethodEnabledLocked(enabledImes.get(i).getId(), false);
                }
                ArrayList<InputMethodInfo> defaultEnabledIme = InputMethodUtils.getDefaultEnabledImes(this.mContext, this.mMethodList);
                int N2 = defaultEnabledIme.size();
                for (int i2 = 0; i2 < N2; i2++) {
                    setInputMethodEnabledLocked(defaultEnabledIme.get(i2).getId(), true);
                }
                updateInputMethodsFromSettingsLocked(true);
                InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(this.mIPackageManager, this.mSettings.getEnabledInputMethodListLocked(), this.mSettings.getCurrentUserId(), this.mContext.getBasePackageName());
                nextIme = this.mSettings.getSelectedInputMethod();
                nextEnabledImes = getEnabledInputMethodList();
            }
            Binder.restoreCallingIdentity(ident);
            PrintWriter pr = shellCommand.getOutPrintWriter();
            pr.println("Reset current and enabled IMEs");
            pr.println("Newly selected IME:");
            pr.print("  ");
            pr.println(nextIme);
            pr.println("Newly enabled IMEs:");
            int N3 = nextEnabledImes.size();
            for (int i3 = 0; i3 < N3; i3++) {
                pr.print("  ");
                pr.println(nextEnabledImes.get(i3).getId());
            }
        }
        return 0;
    }

    private void setInputMethodShownToSettings(Context context, boolean shown) {
        long ident = Binder.clearCallingIdentity();
        try {
            xpSettings.putInt(context, 2, "key_input_method_shown", shown ? 1 : 0);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
