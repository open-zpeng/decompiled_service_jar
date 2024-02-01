package com.android.server.display;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;
import com.android.internal.os.BackgroundThread;
import com.android.server.display.whitebalance.AmbientFilter;
import com.android.server.display.whitebalance.DisplayWhiteBalanceFactory;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/* loaded from: classes.dex */
public class DisplayModeDirector {
    private static final boolean DEBUG = false;
    private static final float EPSILON = 0.001f;
    private static final int GLOBAL_ID = -1;
    private static final int MSG_ALLOWED_MODES_CHANGED = 1;
    private static final int MSG_BRIGHTNESS_THRESHOLDS_CHANGED = 2;
    private static final int MSG_DEFAULT_PEAK_REFRESH_RATE_CHANGED = 3;
    private static final int MSG_REFRESH_RATE_IN_ZONE_CHANGED = 4;
    private static final String TAG = "DisplayModeDirector";
    private final BrightnessObserver mBrightnessObserver;
    private final Context mContext;
    private final DisplayObserver mDisplayObserver;
    private final DisplayModeDirectorHandler mHandler;
    private Listener mListener;
    private final SettingsObserver mSettingsObserver;
    private final Object mLock = new Object();
    private final SparseArray<SparseArray<Vote>> mVotesByDisplay = new SparseArray<>();
    private final SparseArray<Display.Mode[]> mSupportedModesByDisplay = new SparseArray<>();
    private final SparseArray<Display.Mode> mDefaultModeByDisplay = new SparseArray<>();
    private final AppRequestObserver mAppRequestObserver = new AppRequestObserver();
    private final DeviceConfigDisplaySettings mDeviceConfigDisplaySettings = new DeviceConfigDisplaySettings();

    /* loaded from: classes.dex */
    public interface Listener {
        void onAllowedDisplayModesChanged();
    }

    public DisplayModeDirector(Context context, Handler handler) {
        this.mContext = context;
        this.mHandler = new DisplayModeDirectorHandler(handler.getLooper());
        this.mSettingsObserver = new SettingsObserver(context, handler);
        this.mDisplayObserver = new DisplayObserver(context, handler);
        this.mBrightnessObserver = new BrightnessObserver(context, handler);
    }

    public void start(SensorManager sensorManager) {
        this.mSettingsObserver.observe();
        this.mDisplayObserver.observe();
        this.mSettingsObserver.observe();
        this.mBrightnessObserver.observe(sensorManager);
        synchronized (this.mLock) {
            notifyAllowedModesChangedLocked();
        }
    }

    public int[] getAllowedModes(int displayId) {
        synchronized (this.mLock) {
            SparseArray<Vote> votes = getVotesLocked(displayId);
            Display.Mode[] modes = this.mSupportedModesByDisplay.get(displayId);
            Display.Mode defaultMode = this.mDefaultModeByDisplay.get(displayId);
            if (modes != null && defaultMode != null) {
                return getAllowedModesLocked(votes, modes, defaultMode);
            }
            Slog.e(TAG, "Asked about unknown display, returning empty allowed set! (id=" + displayId + ")");
            return new int[0];
        }
    }

    private SparseArray<Vote> getVotesLocked(int displayId) {
        SparseArray<Vote> votes;
        SparseArray<Vote> displayVotes = this.mVotesByDisplay.get(displayId);
        if (displayVotes != null) {
            votes = displayVotes.clone();
        } else {
            votes = new SparseArray<>();
        }
        SparseArray<Vote> globalVotes = this.mVotesByDisplay.get(-1);
        if (globalVotes != null) {
            for (int i = 0; i < globalVotes.size(); i++) {
                int priority = globalVotes.keyAt(i);
                if (votes.indexOfKey(priority) < 0) {
                    votes.put(priority, globalVotes.valueAt(i));
                }
            }
        }
        return votes;
    }

    private int[] getAllowedModesLocked(SparseArray<Vote> votes, Display.Mode[] modes, Display.Mode defaultMode) {
        for (int lowestConsideredPriority = 0; lowestConsideredPriority <= 5; lowestConsideredPriority++) {
            float minRefreshRate = 0.0f;
            float maxRefreshRate = Float.POSITIVE_INFINITY;
            int height = -1;
            int width = -1;
            for (int priority = 5; priority >= lowestConsideredPriority; priority--) {
                Vote vote = votes.get(priority);
                if (vote != null) {
                    minRefreshRate = Math.max(minRefreshRate, vote.minRefreshRate);
                    maxRefreshRate = Math.min(maxRefreshRate, vote.maxRefreshRate);
                    if (height == -1 && width == -1 && vote.height > 0 && vote.width > 0) {
                        width = vote.width;
                        height = vote.height;
                    }
                }
            }
            if (height == -1 || width == -1) {
                width = defaultMode.getPhysicalWidth();
                height = defaultMode.getPhysicalHeight();
            }
            int[] availableModes = filterModes(modes, width, height, minRefreshRate, maxRefreshRate);
            if (availableModes.length > 0) {
                return availableModes;
            }
        }
        return new int[]{defaultMode.getModeId()};
    }

    private int[] filterModes(Display.Mode[] supportedModes, int width, int height, float minRefreshRate, float maxRefreshRate) {
        ArrayList<Display.Mode> availableModes = new ArrayList<>();
        for (Display.Mode mode : supportedModes) {
            if (mode.getPhysicalWidth() == width && mode.getPhysicalHeight() == height) {
                float refreshRate = mode.getRefreshRate();
                if (refreshRate >= minRefreshRate - EPSILON && refreshRate <= EPSILON + maxRefreshRate) {
                    availableModes.add(mode);
                }
            }
        }
        int size = availableModes.size();
        int[] availableModeIds = new int[size];
        for (int i = 0; i < size; i++) {
            availableModeIds[i] = availableModes.get(i).getModeId();
        }
        return availableModeIds;
    }

    public AppRequestObserver getAppRequestObserver() {
        return this.mAppRequestObserver;
    }

    public void setListener(Listener listener) {
        synchronized (this.mLock) {
            this.mListener = listener;
        }
    }

    public void dump(PrintWriter pw) {
        pw.println(TAG);
        synchronized (this.mLock) {
            pw.println("  mSupportedModesByDisplay:");
            for (int i = 0; i < this.mSupportedModesByDisplay.size(); i++) {
                int id = this.mSupportedModesByDisplay.keyAt(i);
                Display.Mode[] modes = this.mSupportedModesByDisplay.valueAt(i);
                pw.println("    " + id + " -> " + Arrays.toString(modes));
            }
            pw.println("  mDefaultModeByDisplay:");
            for (int i2 = 0; i2 < this.mDefaultModeByDisplay.size(); i2++) {
                int id2 = this.mDefaultModeByDisplay.keyAt(i2);
                Display.Mode mode = this.mDefaultModeByDisplay.valueAt(i2);
                pw.println("    " + id2 + " -> " + mode);
            }
            pw.println("  mVotesByDisplay:");
            for (int i3 = 0; i3 < this.mVotesByDisplay.size(); i3++) {
                pw.println("    " + this.mVotesByDisplay.keyAt(i3) + ":");
                SparseArray<Vote> votes = this.mVotesByDisplay.valueAt(i3);
                for (int p = 5; p >= 0; p--) {
                    Vote vote = votes.get(p);
                    if (vote != null) {
                        pw.println("      " + Vote.priorityToString(p) + " -> " + vote);
                    }
                }
            }
            this.mSettingsObserver.dumpLocked(pw);
            this.mAppRequestObserver.dumpLocked(pw);
            this.mBrightnessObserver.dumpLocked(pw);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateVoteLocked(int priority, Vote vote) {
        updateVoteLocked(-1, priority, vote);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateVoteLocked(int displayId, int priority, Vote vote) {
        if (priority < 0 || priority > 5) {
            Slog.w(TAG, "Received a vote with an invalid priority, ignoring: priority=" + Vote.priorityToString(priority) + ", vote=" + vote, new Throwable());
            return;
        }
        SparseArray<Vote> votes = getOrCreateVotesByDisplay(displayId);
        votes.get(priority);
        if (vote != null) {
            votes.put(priority, vote);
        } else {
            votes.remove(priority);
        }
        if (votes.size() == 0) {
            this.mVotesByDisplay.remove(displayId);
        }
        notifyAllowedModesChangedLocked();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyAllowedModesChangedLocked() {
        if (this.mListener != null && !this.mHandler.hasMessages(1)) {
            Message msg = this.mHandler.obtainMessage(1, this.mListener);
            msg.sendToTarget();
        }
    }

    private SparseArray<Vote> getOrCreateVotesByDisplay(int displayId) {
        this.mVotesByDisplay.indexOfKey(displayId);
        if (this.mVotesByDisplay.indexOfKey(displayId) >= 0) {
            return this.mVotesByDisplay.get(displayId);
        }
        SparseArray<Vote> votes = new SparseArray<>();
        this.mVotesByDisplay.put(displayId, votes);
        return votes;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class DisplayModeDirectorHandler extends Handler {
        DisplayModeDirectorHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i == 1) {
                Listener listener = (Listener) msg.obj;
                listener.onAllowedDisplayModesChanged();
            } else if (i == 2) {
                Pair<int[], int[]> thresholds = (Pair) msg.obj;
                if (thresholds != null) {
                    DisplayModeDirector.this.mBrightnessObserver.onDeviceConfigThresholdsChanged((int[]) thresholds.first, (int[]) thresholds.second);
                } else {
                    DisplayModeDirector.this.mBrightnessObserver.onDeviceConfigThresholdsChanged(null, null);
                }
            } else if (i == 3) {
                Float defaultPeakRefreshRate = (Float) msg.obj;
                DisplayModeDirector.this.mSettingsObserver.onDeviceConfigDefaultPeakRefreshRateChanged(defaultPeakRefreshRate);
            } else if (i == 4) {
                int refreshRateInZone = msg.arg1;
                DisplayModeDirector.this.mBrightnessObserver.onDeviceConfigRefreshRateInZoneChanged(refreshRateInZone);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class Vote {
        public static final int INVALID_SIZE = -1;
        public static final int MAX_PRIORITY = 5;
        public static final int MIN_PRIORITY = 0;
        public static final int PRIORITY_APP_REQUEST_REFRESH_RATE = 2;
        public static final int PRIORITY_APP_REQUEST_SIZE = 3;
        public static final int PRIORITY_LOW_BRIGHTNESS = 0;
        public static final int PRIORITY_LOW_POWER_MODE = 5;
        public static final int PRIORITY_USER_SETTING_MIN_REFRESH_RATE = 1;
        public static final int PRIORITY_USER_SETTING_PEAK_REFRESH_RATE = 4;
        public final int height;
        public final float maxRefreshRate;
        public final float minRefreshRate;
        public final int width;

        public static Vote forRefreshRates(float minRefreshRate, float maxRefreshRate) {
            return new Vote(-1, -1, minRefreshRate, maxRefreshRate);
        }

        public static Vote forSize(int width, int height) {
            return new Vote(width, height, 0.0f, Float.POSITIVE_INFINITY);
        }

        private Vote(int width, int height, float minRefreshRate, float maxRefreshRate) {
            this.width = width;
            this.height = height;
            this.minRefreshRate = minRefreshRate;
            this.maxRefreshRate = maxRefreshRate;
        }

        public static String priorityToString(int priority) {
            if (priority != 0) {
                if (priority != 1) {
                    if (priority != 2) {
                        if (priority != 3) {
                            if (priority != 4) {
                                if (priority == 5) {
                                    return "PRIORITY_LOW_POWER_MODE";
                                }
                                return Integer.toString(priority);
                            }
                            return "PRIORITY_USER_SETTING_PEAK_REFRESH_RATE";
                        }
                        return "PRIORITY_APP_REQUEST_SIZE";
                    }
                    return "PRIORITY_APP_REQUEST_REFRESH_RATE";
                }
                return "PRIORITY_USER_SETTING_MIN_REFRESH_RATE";
            }
            return "PRIORITY_LOW_BRIGHTNESS";
        }

        public String toString() {
            return "Vote{width=" + this.width + ", height=" + this.height + ", minRefreshRate=" + this.minRefreshRate + ", maxRefreshRate=" + this.maxRefreshRate + "}";
        }
    }

    /* loaded from: classes.dex */
    private final class SettingsObserver extends ContentObserver {
        private final Context mContext;
        private float mDefaultPeakRefreshRate;
        private final Uri mLowPowerModeSetting;
        private final Uri mMinRefreshRateSetting;
        private final Uri mPeakRefreshRateSetting;

        SettingsObserver(Context context, Handler handler) {
            super(handler);
            this.mPeakRefreshRateSetting = Settings.System.getUriFor("peak_refresh_rate");
            this.mMinRefreshRateSetting = Settings.System.getUriFor("min_refresh_rate");
            this.mLowPowerModeSetting = Settings.Global.getUriFor("low_power");
            this.mContext = context;
            this.mDefaultPeakRefreshRate = context.getResources().getInteger(17694779);
        }

        public void observe() {
            ContentResolver cr = this.mContext.getContentResolver();
            cr.registerContentObserver(this.mPeakRefreshRateSetting, false, this, 0);
            cr.registerContentObserver(this.mMinRefreshRateSetting, false, this, 0);
            cr.registerContentObserver(this.mLowPowerModeSetting, false, this, 0);
            Float deviceConfigDefaultPeakRefresh = DisplayModeDirector.this.mDeviceConfigDisplaySettings.getDefaultPeakRefreshRate();
            if (deviceConfigDefaultPeakRefresh != null) {
                this.mDefaultPeakRefreshRate = deviceConfigDefaultPeakRefresh.floatValue();
            }
            synchronized (DisplayModeDirector.this.mLock) {
                updateRefreshRateSettingLocked();
                updateLowPowerModeSettingLocked();
            }
        }

        public void onDeviceConfigDefaultPeakRefreshRateChanged(Float defaultPeakRefreshRate) {
            if (defaultPeakRefreshRate == null) {
                defaultPeakRefreshRate = Float.valueOf(this.mContext.getResources().getInteger(17694779));
            }
            if (this.mDefaultPeakRefreshRate != defaultPeakRefreshRate.floatValue()) {
                synchronized (DisplayModeDirector.this.mLock) {
                    this.mDefaultPeakRefreshRate = defaultPeakRefreshRate.floatValue();
                    updateRefreshRateSettingLocked();
                }
            }
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri, int userId) {
            synchronized (DisplayModeDirector.this.mLock) {
                if (!this.mPeakRefreshRateSetting.equals(uri) && !this.mMinRefreshRateSetting.equals(uri)) {
                    if (this.mLowPowerModeSetting.equals(uri)) {
                        updateLowPowerModeSettingLocked();
                    }
                }
                updateRefreshRateSettingLocked();
            }
        }

        private void updateLowPowerModeSettingLocked() {
            Vote vote;
            boolean inLowPowerMode = Settings.Global.getInt(this.mContext.getContentResolver(), "low_power", 0) != 0;
            if (inLowPowerMode) {
                vote = Vote.forRefreshRates(0.0f, 60.0f);
            } else {
                vote = null;
            }
            DisplayModeDirector.this.updateVoteLocked(5, vote);
            DisplayModeDirector.this.mBrightnessObserver.onLowPowerModeEnabledLocked(inLowPowerMode);
        }

        private void updateRefreshRateSettingLocked() {
            float minRefreshRate = Settings.System.getFloat(this.mContext.getContentResolver(), "min_refresh_rate", 0.0f);
            float peakRefreshRate = Settings.System.getFloat(this.mContext.getContentResolver(), "peak_refresh_rate", this.mDefaultPeakRefreshRate);
            DisplayModeDirector.this.updateVoteLocked(4, Vote.forRefreshRates(0.0f, Math.max(minRefreshRate, peakRefreshRate)));
            DisplayModeDirector.this.updateVoteLocked(1, Vote.forRefreshRates(minRefreshRate, Float.POSITIVE_INFINITY));
            DisplayModeDirector.this.mBrightnessObserver.onRefreshRateSettingChangedLocked(minRefreshRate, peakRefreshRate);
        }

        public void dumpLocked(PrintWriter pw) {
            pw.println("  SettingsObserver");
            pw.println("    mDefaultPeakRefreshRate: " + this.mDefaultPeakRefreshRate);
        }
    }

    /* loaded from: classes.dex */
    final class AppRequestObserver {
        private SparseArray<Display.Mode> mAppRequestedModeByDisplay = new SparseArray<>();

        AppRequestObserver() {
        }

        public void setAppRequestedMode(int displayId, int modeId) {
            synchronized (DisplayModeDirector.this.mLock) {
                setAppRequestedModeLocked(displayId, modeId);
            }
        }

        private void setAppRequestedModeLocked(int displayId, int modeId) {
            Vote refreshRateVote;
            Vote sizeVote;
            Display.Mode requestedMode = findModeByIdLocked(displayId, modeId);
            if (Objects.equals(requestedMode, this.mAppRequestedModeByDisplay.get(displayId))) {
                return;
            }
            if (requestedMode != null) {
                this.mAppRequestedModeByDisplay.put(displayId, requestedMode);
                float refreshRate = requestedMode.getRefreshRate();
                refreshRateVote = Vote.forRefreshRates(refreshRate, refreshRate);
                sizeVote = Vote.forSize(requestedMode.getPhysicalWidth(), requestedMode.getPhysicalHeight());
            } else {
                this.mAppRequestedModeByDisplay.remove(displayId);
                refreshRateVote = null;
                sizeVote = null;
            }
            DisplayModeDirector.this.updateVoteLocked(displayId, 2, refreshRateVote);
            DisplayModeDirector.this.updateVoteLocked(displayId, 3, sizeVote);
        }

        private Display.Mode findModeByIdLocked(int displayId, int modeId) {
            Display.Mode[] modes = (Display.Mode[]) DisplayModeDirector.this.mSupportedModesByDisplay.get(displayId);
            if (modes == null) {
                return null;
            }
            for (Display.Mode mode : modes) {
                if (mode.getModeId() == modeId) {
                    return mode;
                }
            }
            return null;
        }

        public void dumpLocked(PrintWriter pw) {
            pw.println("  AppRequestObserver");
            pw.println("    mAppRequestedModeByDisplay:");
            for (int i = 0; i < this.mAppRequestedModeByDisplay.size(); i++) {
                int id = this.mAppRequestedModeByDisplay.keyAt(i);
                Display.Mode mode = this.mAppRequestedModeByDisplay.valueAt(i);
                pw.println("    " + id + " -> " + mode);
            }
        }
    }

    /* loaded from: classes.dex */
    private final class DisplayObserver implements DisplayManager.DisplayListener {
        private final Context mContext;
        private final Handler mHandler;

        DisplayObserver(Context context, Handler handler) {
            this.mContext = context;
            this.mHandler = handler;
        }

        public void observe() {
            DisplayManager dm = (DisplayManager) this.mContext.getSystemService(DisplayManager.class);
            dm.registerDisplayListener(this, this.mHandler);
            SparseArray<Display.Mode[]> modes = new SparseArray<>();
            SparseArray<Display.Mode> defaultModes = new SparseArray<>();
            DisplayInfo info = new DisplayInfo();
            Display[] displays = dm.getDisplays();
            for (Display d : displays) {
                int displayId = d.getDisplayId();
                d.getDisplayInfo(info);
                modes.put(displayId, info.supportedModes);
                defaultModes.put(displayId, info.getDefaultMode());
            }
            synchronized (DisplayModeDirector.this.mLock) {
                int size = modes.size();
                for (int i = 0; i < size; i++) {
                    DisplayModeDirector.this.mSupportedModesByDisplay.put(modes.keyAt(i), modes.valueAt(i));
                    DisplayModeDirector.this.mDefaultModeByDisplay.put(defaultModes.keyAt(i), defaultModes.valueAt(i));
                }
            }
        }

        @Override // android.hardware.display.DisplayManager.DisplayListener
        public void onDisplayAdded(int displayId) {
            updateDisplayModes(displayId);
        }

        @Override // android.hardware.display.DisplayManager.DisplayListener
        public void onDisplayRemoved(int displayId) {
            synchronized (DisplayModeDirector.this.mLock) {
                DisplayModeDirector.this.mSupportedModesByDisplay.remove(displayId);
                DisplayModeDirector.this.mDefaultModeByDisplay.remove(displayId);
            }
        }

        @Override // android.hardware.display.DisplayManager.DisplayListener
        public void onDisplayChanged(int displayId) {
            updateDisplayModes(displayId);
            DisplayModeDirector.this.mBrightnessObserver.onDisplayChanged(displayId);
        }

        private void updateDisplayModes(int displayId) {
            Display d = ((DisplayManager) this.mContext.getSystemService(DisplayManager.class)).getDisplay(displayId);
            if (d == null) {
                return;
            }
            DisplayInfo info = new DisplayInfo();
            d.getDisplayInfo(info);
            boolean changed = false;
            synchronized (DisplayModeDirector.this.mLock) {
                if (!Arrays.equals((Object[]) DisplayModeDirector.this.mSupportedModesByDisplay.get(displayId), info.supportedModes)) {
                    DisplayModeDirector.this.mSupportedModesByDisplay.put(displayId, info.supportedModes);
                    changed = true;
                }
                if (!Objects.equals(DisplayModeDirector.this.mDefaultModeByDisplay.get(displayId), info.getDefaultMode())) {
                    changed = true;
                    DisplayModeDirector.this.mDefaultModeByDisplay.put(displayId, info.getDefaultMode());
                }
                if (changed) {
                    DisplayModeDirector.this.notifyAllowedModesChangedLocked();
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class BrightnessObserver extends ContentObserver {
        private static final int LIGHT_SENSOR_RATE_MS = 250;
        private int[] mAmbientBrightnessThresholds;
        private AmbientFilter mAmbientFilter;
        private float mAmbientLux;
        private final Context mContext;
        private final Uri mDisplayBrightnessSetting;
        private int[] mDisplayBrightnessThresholds;
        private Sensor mLightSensor;
        private LightSensorEventListener mLightSensorListener;
        private boolean mLowPowerModeEnabled;
        private boolean mRefreshRateChangeable;
        private int mRefreshRateInZone;
        private boolean mScreenOn;
        private SensorManager mSensorManager;
        private boolean mShouldObserveAmbientChange;
        private boolean mShouldObserveDisplayChange;

        BrightnessObserver(Context context, Handler handler) {
            super(handler);
            this.mDisplayBrightnessSetting = Settings.System.getUriFor("screen_brightness");
            this.mLightSensorListener = new LightSensorEventListener();
            this.mAmbientLux = -1.0f;
            this.mScreenOn = false;
            this.mRefreshRateChangeable = false;
            this.mLowPowerModeEnabled = false;
            this.mContext = context;
            this.mDisplayBrightnessThresholds = context.getResources().getIntArray(17235995);
            this.mAmbientBrightnessThresholds = context.getResources().getIntArray(17235983);
            if (this.mDisplayBrightnessThresholds.length != this.mAmbientBrightnessThresholds.length) {
                throw new RuntimeException("display brightness threshold array and ambient brightness threshold array have different length");
            }
        }

        public void observe(SensorManager sensorManager) {
            this.mSensorManager = sensorManager;
            int[] brightnessThresholds = DisplayModeDirector.this.mDeviceConfigDisplaySettings.getBrightnessThresholds();
            int[] ambientThresholds = DisplayModeDirector.this.mDeviceConfigDisplaySettings.getAmbientThresholds();
            if (brightnessThresholds != null && ambientThresholds != null && brightnessThresholds.length == ambientThresholds.length) {
                this.mDisplayBrightnessThresholds = brightnessThresholds;
                this.mAmbientBrightnessThresholds = ambientThresholds;
            }
            this.mRefreshRateInZone = DisplayModeDirector.this.mDeviceConfigDisplaySettings.getRefreshRateInZone();
            restartObserver();
            DisplayModeDirector.this.mDeviceConfigDisplaySettings.startListening();
        }

        public void onRefreshRateSettingChangedLocked(float min, float max) {
            boolean changeable = max - min > 1.0f && max > 60.0f;
            if (this.mRefreshRateChangeable != changeable) {
                this.mRefreshRateChangeable = changeable;
                updateSensorStatus();
                if (!changeable) {
                    DisplayModeDirector.this.updateVoteLocked(0, null);
                }
            }
        }

        public void onLowPowerModeEnabledLocked(boolean b) {
            if (this.mLowPowerModeEnabled != b) {
                this.mLowPowerModeEnabled = b;
                updateSensorStatus();
            }
        }

        public void onDeviceConfigThresholdsChanged(int[] brightnessThresholds, int[] ambientThresholds) {
            if (brightnessThresholds != null && ambientThresholds != null && brightnessThresholds.length == ambientThresholds.length) {
                this.mDisplayBrightnessThresholds = brightnessThresholds;
                this.mAmbientBrightnessThresholds = ambientThresholds;
            } else {
                this.mDisplayBrightnessThresholds = this.mContext.getResources().getIntArray(17235995);
                this.mAmbientBrightnessThresholds = this.mContext.getResources().getIntArray(17235983);
            }
            restartObserver();
        }

        public void onDeviceConfigRefreshRateInZoneChanged(int refreshRate) {
            if (refreshRate != this.mRefreshRateInZone) {
                this.mRefreshRateInZone = refreshRate;
                restartObserver();
            }
        }

        public void dumpLocked(PrintWriter pw) {
            int[] iArr;
            int[] iArr2;
            pw.println("  BrightnessObserver");
            pw.println("    mRefreshRateInZone: " + this.mRefreshRateInZone);
            for (int d : this.mDisplayBrightnessThresholds) {
                pw.println("    mDisplayBrightnessThreshold: " + d);
            }
            for (int d2 : this.mAmbientBrightnessThresholds) {
                pw.println("    mAmbientBrightnessThreshold: " + d2);
            }
        }

        public void onDisplayChanged(int displayId) {
            if (displayId == 0) {
                onScreenOn(isDefaultDisplayOn());
            }
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri, int userId) {
            synchronized (DisplayModeDirector.this.mLock) {
                onBrightnessChangedLocked();
            }
        }

        private void restartObserver() {
            this.mShouldObserveDisplayChange = checkShouldObserve(this.mDisplayBrightnessThresholds);
            this.mShouldObserveAmbientChange = checkShouldObserve(this.mAmbientBrightnessThresholds);
            ContentResolver cr = this.mContext.getContentResolver();
            if (this.mShouldObserveDisplayChange) {
                cr.unregisterContentObserver(this);
                cr.registerContentObserver(this.mDisplayBrightnessSetting, false, this, 0);
            } else {
                cr.unregisterContentObserver(this);
            }
            if (this.mShouldObserveAmbientChange) {
                Resources resources = this.mContext.getResources();
                String lightSensorType = resources.getString(17039723);
                Sensor lightSensor = null;
                if (!TextUtils.isEmpty(lightSensorType)) {
                    List<Sensor> sensors = this.mSensorManager.getSensorList(-1);
                    int i = 0;
                    while (true) {
                        if (i >= sensors.size()) {
                            break;
                        }
                        Sensor sensor = sensors.get(i);
                        if (!lightSensorType.equals(sensor.getStringType())) {
                            i++;
                        } else {
                            lightSensor = sensor;
                            break;
                        }
                    }
                }
                if (lightSensor == null) {
                    lightSensor = this.mSensorManager.getDefaultSensor(5);
                }
                if (lightSensor != null) {
                    Resources res = this.mContext.getResources();
                    this.mAmbientFilter = DisplayWhiteBalanceFactory.createBrightnessFilter(res);
                    this.mLightSensor = lightSensor;
                    onScreenOn(isDefaultDisplayOn());
                }
            } else {
                this.mAmbientFilter = null;
                this.mLightSensor = null;
            }
            if (this.mRefreshRateChangeable) {
                updateSensorStatus();
                synchronized (DisplayModeDirector.this.mLock) {
                    onBrightnessChangedLocked();
                }
            }
        }

        private boolean checkShouldObserve(int[] a) {
            if (this.mRefreshRateInZone <= 0) {
                return false;
            }
            for (int d : a) {
                if (d >= 0) {
                    return true;
                }
            }
            return false;
        }

        private boolean isInsideZone(int brightness, float lux) {
            int i = 0;
            while (true) {
                int[] iArr = this.mDisplayBrightnessThresholds;
                if (i < iArr.length) {
                    int disp = iArr[i];
                    int ambi = this.mAmbientBrightnessThresholds[i];
                    if (disp >= 0 && ambi >= 0) {
                        if (brightness <= disp && this.mAmbientLux <= ambi) {
                            return true;
                        }
                    } else if (disp >= 0) {
                        if (brightness <= disp) {
                            return true;
                        }
                    } else if (ambi >= 0 && this.mAmbientLux <= ambi) {
                        return true;
                    }
                    i++;
                } else {
                    return false;
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void onBrightnessChangedLocked() {
            int brightness = Settings.System.getInt(this.mContext.getContentResolver(), "screen_brightness", -1);
            Vote vote = null;
            boolean insideZone = isInsideZone(brightness, this.mAmbientLux);
            if (insideZone) {
                int i = this.mRefreshRateInZone;
                vote = Vote.forRefreshRates(i, i);
            }
            DisplayModeDirector.this.updateVoteLocked(0, vote);
        }

        private void onScreenOn(boolean on) {
            if (this.mScreenOn != on) {
                this.mScreenOn = on;
                updateSensorStatus();
            }
        }

        private void updateSensorStatus() {
            LightSensorEventListener lightSensorEventListener;
            SensorManager sensorManager = this.mSensorManager;
            if (sensorManager == null || (lightSensorEventListener = this.mLightSensorListener) == null) {
                return;
            }
            if (this.mShouldObserveAmbientChange && this.mScreenOn && !this.mLowPowerModeEnabled && this.mRefreshRateChangeable) {
                sensorManager.registerListener(lightSensorEventListener, this.mLightSensor, 250000, DisplayModeDirector.this.mHandler);
                return;
            }
            this.mLightSensorListener.removeCallbacks();
            this.mSensorManager.unregisterListener(this.mLightSensorListener);
        }

        private boolean isDefaultDisplayOn() {
            Display display = ((DisplayManager) this.mContext.getSystemService(DisplayManager.class)).getDisplay(0);
            return display.getState() != 1 && ((PowerManager) this.mContext.getSystemService(PowerManager.class)).isInteractive();
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes.dex */
        public final class LightSensorEventListener implements SensorEventListener {
            private static final int INJECT_EVENTS_INTERVAL_MS = 250;
            private Runnable mInjectSensorEventRunnable;
            private float mLastSensorData;

            private LightSensorEventListener() {
                this.mInjectSensorEventRunnable = new Runnable() { // from class: com.android.server.display.DisplayModeDirector.BrightnessObserver.LightSensorEventListener.1
                    @Override // java.lang.Runnable
                    public void run() {
                        long now = SystemClock.uptimeMillis();
                        LightSensorEventListener.this.processSensorData(now);
                        LightSensorEventListener lightSensorEventListener = LightSensorEventListener.this;
                        if (lightSensorEventListener.isDifferentZone(lightSensorEventListener.mLastSensorData, BrightnessObserver.this.mAmbientLux)) {
                            DisplayModeDirector.this.mHandler.postDelayed(LightSensorEventListener.this.mInjectSensorEventRunnable, 250L);
                        }
                    }
                };
            }

            @Override // android.hardware.SensorEventListener
            public void onSensorChanged(SensorEvent event) {
                this.mLastSensorData = event.values[0];
                boolean zoneChanged = isDifferentZone(this.mLastSensorData, BrightnessObserver.this.mAmbientLux);
                if (zoneChanged && this.mLastSensorData < BrightnessObserver.this.mAmbientLux) {
                    BrightnessObserver.this.mAmbientFilter.clear();
                }
                long now = SystemClock.uptimeMillis();
                BrightnessObserver.this.mAmbientFilter.addValue(now, this.mLastSensorData);
                DisplayModeDirector.this.mHandler.removeCallbacks(this.mInjectSensorEventRunnable);
                processSensorData(now);
                if (zoneChanged && this.mLastSensorData > BrightnessObserver.this.mAmbientLux) {
                    DisplayModeDirector.this.mHandler.postDelayed(this.mInjectSensorEventRunnable, 250L);
                }
            }

            @Override // android.hardware.SensorEventListener
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }

            public void removeCallbacks() {
                DisplayModeDirector.this.mHandler.removeCallbacks(this.mInjectSensorEventRunnable);
            }

            /* JADX INFO: Access modifiers changed from: private */
            public void processSensorData(long now) {
                BrightnessObserver brightnessObserver = BrightnessObserver.this;
                brightnessObserver.mAmbientLux = brightnessObserver.mAmbientFilter.getEstimate(now);
                synchronized (DisplayModeDirector.this.mLock) {
                    BrightnessObserver.this.onBrightnessChangedLocked();
                }
            }

            /* JADX INFO: Access modifiers changed from: private */
            public boolean isDifferentZone(float lux1, float lux2) {
                for (int z = 0; z < BrightnessObserver.this.mAmbientBrightnessThresholds.length; z++) {
                    float boundary = BrightnessObserver.this.mAmbientBrightnessThresholds[z];
                    if (lux1 <= boundary && lux2 > boundary) {
                        return true;
                    }
                    if (lux1 > boundary && lux2 <= boundary) {
                        return true;
                    }
                }
                return false;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class DeviceConfigDisplaySettings implements DeviceConfig.OnPropertiesChangedListener {
        public DeviceConfigDisplaySettings() {
        }

        public void startListening() {
            DeviceConfig.addOnPropertiesChangedListener("display_manager", BackgroundThread.getExecutor(), this);
        }

        public int[] getBrightnessThresholds() {
            return getIntArrayProperty("peak_refresh_rate_brightness_thresholds");
        }

        public int[] getAmbientThresholds() {
            return getIntArrayProperty("peak_refresh_rate_ambient_thresholds");
        }

        public Float getDefaultPeakRefreshRate() {
            float defaultPeakRefreshRate = DeviceConfig.getFloat("display_manager", "peak_refresh_rate_default", -1.0f);
            if (defaultPeakRefreshRate == -1.0f) {
                return null;
            }
            return Float.valueOf(defaultPeakRefreshRate);
        }

        public int getRefreshRateInZone() {
            int defaultRefreshRateInZone = DisplayModeDirector.this.mContext.getResources().getInteger(17694781);
            int refreshRate = DeviceConfig.getInt("display_manager", "refresh_rate_in_zone", defaultRefreshRateInZone);
            return refreshRate;
        }

        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            int[] brightnessThresholds = getBrightnessThresholds();
            int[] ambientThresholds = getAmbientThresholds();
            Float defaultPeakRefreshRate = getDefaultPeakRefreshRate();
            int refreshRateInZone = getRefreshRateInZone();
            DisplayModeDirector.this.mHandler.obtainMessage(2, new Pair(brightnessThresholds, ambientThresholds)).sendToTarget();
            DisplayModeDirector.this.mHandler.obtainMessage(3, defaultPeakRefreshRate).sendToTarget();
            DisplayModeDirector.this.mHandler.obtainMessage(4, refreshRateInZone, 0).sendToTarget();
        }

        private int[] getIntArrayProperty(String prop) {
            String strArray = DeviceConfig.getString("display_manager", prop, (String) null);
            if (strArray == null) {
                return null;
            }
            return parseIntArray(strArray);
        }

        private int[] parseIntArray(String strArray) {
            String[] items = strArray.split(",");
            int[] array = new int[items.length];
            for (int i = 0; i < array.length; i++) {
                try {
                    array[i] = Integer.parseInt(items[i]);
                } catch (NumberFormatException e) {
                    Slog.e(DisplayModeDirector.TAG, "Incorrect format for array: '" + strArray + "'", e);
                    return null;
                }
            }
            return array;
        }
    }
}
