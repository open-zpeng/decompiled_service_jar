package com.android.server.lights;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.Trace;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.MathUtils;
import android.util.Slog;
import android.view.SurfaceControl;
import com.android.server.SystemService;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/* loaded from: classes.dex */
public class LightsService extends SystemService {
    static final boolean DEBUG = false;
    static final String TAG = "LightsService";
    private BrightnessHandler mBrightnessHandler;
    private Handler mH;
    final LightImpl[] mLights;
    private final LightsManager mService;

    static native void setLight_native(int i, int i2, int i3, int i4, int i5, int i6);

    /* loaded from: classes.dex */
    private final class LightImpl extends Light {
        private int mBrightnessMode;
        private int mColor;
        private final IBinder mDisplayToken;
        private boolean mFlashing;
        private int mId;
        private boolean mInitialized;
        private int mLastBrightnessMode;
        private int mLastColor;
        private int mMode;
        private int mOffMS;
        private int mOnMS;
        private final int mSurfaceControlMaximumBrightness;
        private boolean mUseLowPersistenceForVR;
        private boolean mVrModeEnabled;

        private LightImpl(Context context, int id) {
            PowerManager pm;
            this.mId = id;
            this.mDisplayToken = SurfaceControl.getInternalDisplayToken();
            boolean brightnessSupport = SurfaceControl.getDisplayBrightnessSupport(this.mDisplayToken);
            int maximumBrightness = 0;
            if (brightnessSupport && (pm = (PowerManager) context.getSystemService(PowerManager.class)) != null) {
                maximumBrightness = pm.getMaximumScreenBrightnessSetting();
            }
            this.mSurfaceControlMaximumBrightness = maximumBrightness;
        }

        @Override // com.android.server.lights.Light
        public void setBrightness(int brightness) {
            setBrightness(brightness, 0);
        }

        @Override // com.android.server.lights.Light
        public void setBrightness(int brightness, int brightnessMode) {
            synchronized (this) {
                if (brightnessMode == 2) {
                    Slog.w(LightsService.TAG, "setBrightness with LOW_PERSISTENCE unexpected #" + this.mId + ": brightness=0x" + Integer.toHexString(brightness));
                    return;
                }
                int screenBrightness = 0;
                try {
                    if (brightness == DisplayManager.getOverrideBrightness(0)) {
                        brightness = 0;
                    }
                    int value = Settings.System.getIntForUser(LightsService.this.getContext().getContentResolver(), "screen_brightness", 0, -2);
                    screenBrightness = MathUtils.constrain(value, 0, 255);
                } catch (Exception e) {
                    Slog.w(LightsService.TAG, e.getMessage());
                }
                LightsService.this.mBrightnessHandler.writeBrightnessToFile("/sys/class/backlight/panel0-backlight/brightness", brightness, screenBrightness);
            }
        }

        @Override // com.android.server.lights.Light
        public void setBrightness(int brightness, String file) {
            synchronized (this) {
                int realBrightness = brightness;
                if (brightness != 0) {
                    realBrightness = DisplayManager.getOverrideBrightness(brightness);
                }
                LightsService.this.mBrightnessHandler.writeBrightnessToFile(file, realBrightness, brightness);
            }
        }

        @Override // com.android.server.lights.Light
        public void setColor(int color) {
            synchronized (this) {
                setLightLocked(color, 0, 0, 0, 0);
            }
        }

        @Override // com.android.server.lights.Light
        public void setFlashing(int color, int mode, int onMS, int offMS) {
            synchronized (this) {
                setLightLocked(color, mode, onMS, offMS, 0);
            }
        }

        @Override // com.android.server.lights.Light
        public void pulse() {
            pulse(16777215, 7);
        }

        @Override // com.android.server.lights.Light
        public void pulse(int color, int onMS) {
            synchronized (this) {
                if (this.mColor == 0 && !this.mFlashing) {
                    setLightLocked(color, 2, onMS, 1000, 0);
                    this.mColor = 0;
                    LightsService.this.mH.sendMessageDelayed(Message.obtain(LightsService.this.mH, 1, this), onMS);
                }
            }
        }

        @Override // com.android.server.lights.Light
        public void turnOff() {
            synchronized (this) {
                setLightLocked(0, 0, 0, 0, 0);
            }
        }

        @Override // com.android.server.lights.Light
        public void setVrMode(boolean enabled) {
            synchronized (this) {
                if (this.mVrModeEnabled != enabled) {
                    this.mVrModeEnabled = enabled;
                    this.mUseLowPersistenceForVR = LightsService.this.getVrDisplayMode() == 0;
                    if (shouldBeInLowPersistenceMode()) {
                        this.mLastBrightnessMode = this.mBrightnessMode;
                    }
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void stopFlashing() {
            synchronized (this) {
                setLightLocked(this.mColor, 0, 0, 0, 0);
            }
        }

        private void setLightLocked(int color, int mode, int onMS, int offMS, int brightnessMode) {
            if (shouldBeInLowPersistenceMode()) {
                brightnessMode = 2;
            } else if (brightnessMode == 2) {
                brightnessMode = this.mLastBrightnessMode;
            }
            if (!this.mInitialized || color != this.mColor || mode != this.mMode || onMS != this.mOnMS || offMS != this.mOffMS || this.mBrightnessMode != brightnessMode) {
                this.mInitialized = true;
                this.mLastColor = this.mColor;
                this.mColor = color;
                this.mMode = mode;
                this.mOnMS = onMS;
                this.mOffMS = offMS;
                this.mBrightnessMode = brightnessMode;
                Trace.traceBegin(131072L, "setLight(" + this.mId + ", 0x" + Integer.toHexString(color) + ")");
                try {
                    LightsService.setLight_native(this.mId, color, mode, onMS, offMS, brightnessMode);
                } finally {
                    Trace.traceEnd(131072L);
                }
            }
        }

        private boolean shouldBeInLowPersistenceMode() {
            return this.mVrModeEnabled && this.mUseLowPersistenceForVR;
        }
    }

    public LightsService(Context context) {
        super(context);
        this.mLights = new LightImpl[8];
        this.mService = new LightsManager() { // from class: com.android.server.lights.LightsService.1
            @Override // com.android.server.lights.LightsManager
            public Light getLight(int id) {
                if (id >= 0 && id < 8) {
                    return LightsService.this.mLights[id];
                }
                return null;
            }
        };
        this.mH = new Handler() { // from class: com.android.server.lights.LightsService.2
            @Override // android.os.Handler
            public void handleMessage(Message msg) {
                LightImpl light = (LightImpl) msg.obj;
                light.stopFlashing();
            }
        };
        this.mBrightnessHandler = new BrightnessHandler();
        for (int i = 0; i < 8; i++) {
            this.mLights[i] = new LightImpl(context, i);
        }
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishLocalService(LightsManager.class, this.mService);
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getVrDisplayMode() {
        int currentUser = ActivityManager.getCurrentUser();
        return Settings.Secure.getIntForUser(getContext().getContentResolver(), "vr_display_mode", 0, currentUser);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class BrightnessHandler {
        private Object lock;
        private HashMap<String, Long> mLogIntervalMap;
        private ArrayMap<String, FileOutputStream> mStreamMap;

        private BrightnessHandler() {
            this.mStreamMap = new ArrayMap<>();
            this.mLogIntervalMap = new HashMap<>();
            this.lock = new Object();
        }

        public void writeBrightnessToFile(String fileName, int brightness, int screenBrightness) {
            FileOutputStream fileOutputStream = getFileOutStream(fileName, false);
            if (fileOutputStream == null) {
                return;
            }
            for (int i = 0; i < 3; i++) {
                try {
                    fileOutputStream.write(String.valueOf(brightness).getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    Slog.i(LightsService.TAG, "writeBrightnessToFile: " + e.getMessage());
                    try {
                        fileOutputStream.close();
                        Thread.sleep(50L);
                        fileOutputStream = getFileOutStream(fileName, true);
                    } catch (Exception ex) {
                        Slog.i(LightsService.TAG, "failed exception: " + ex.getMessage());
                    }
                }
            }
            try {
                synchronized (this.lock) {
                    boolean canPrintLog = false;
                    if (brightness == 0 || screenBrightness == 0) {
                        canPrintLog = true;
                    } else if (brightness == DisplayManager.getOverrideBrightness(screenBrightness)) {
                        long interval = System.currentTimeMillis() - this.mLogIntervalMap.getOrDefault(fileName, 0L).longValue();
                        if (interval > 1000) {
                            canPrintLog = true;
                        }
                    }
                    if (canPrintLog) {
                        this.mLogIntervalMap.put(fileName, Long.valueOf(System.currentTimeMillis()));
                        StringBuilder builder = new StringBuilder();
                        if ("/sys/class/backlight/panel0-backlight/brightness".equals(fileName)) {
                            builder.append("new setBrightness: brightness=0x");
                        } else if ("/sys/class/backlight/panel2-backlight/brightness".equals(fileName)) {
                            builder.append("new passenger setBrightness: brightness=0x");
                        } else if ("/sys/class/backlight/panel1-backlight/brightness".equals(fileName)) {
                            builder.append("new icm setBrightness: brightness=0x");
                        }
                        builder.append(Integer.toHexString(brightness));
                        builder.append(", screenBrightness: " + screenBrightness);
                        Slog.i(LightsService.TAG, builder.toString());
                    }
                }
            } catch (Exception e2) {
            }
        }

        private FileOutputStream getFileOutStream(String fileName, boolean isError) {
            FileOutputStream outputStream = this.mStreamMap.get(fileName);
            if (isError) {
                outputStream = null;
            }
            if (outputStream == null) {
                Slog.i(LightsService.TAG, fileName + " fileStream is null");
                try {
                    outputStream = new FileOutputStream(fileName);
                    this.mStreamMap.put(fileName, outputStream);
                    return outputStream;
                } catch (Exception e) {
                    Slog.i(LightsService.TAG, "new " + fileName + " stream fail: " + e.getMessage());
                    return outputStream;
                }
            }
            return outputStream;
        }
    }
}
