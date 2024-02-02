package com.android.server.lights;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.os.Trace;
import android.provider.Settings;
import android.util.Slog;
import com.android.server.SystemService;
/* loaded from: classes.dex */
public class LightsService extends SystemService {
    static final boolean DEBUG = false;
    static final String TAG = "LightsService";
    private Handler mH;
    final LightImpl[] mLights;
    private final LightsManager mService;

    static native void setLight_native(int i, int i2, int i3, int i4, int i5, int i6);

    /* loaded from: classes.dex */
    private final class LightImpl extends Light {
        private int mBrightnessMode;
        private int mColor;
        private boolean mFlashing;
        private int mId;
        private boolean mInitialized;
        private int mLastBrightnessMode;
        private int mLastColor;
        private int mMode;
        private int mOffMS;
        private int mOnMS;
        private boolean mUseLowPersistenceForVR;
        private boolean mVrModeEnabled;

        private LightImpl(int id) {
            this.mId = id;
        }

        @Override // com.android.server.lights.Light
        public void setBrightness(int brightness) {
            setBrightness(brightness, 0);
        }

        @Override // com.android.server.lights.Light
        public void setBrightness(int brightness, int brightnessMode) {
            synchronized (this) {
                try {
                    if (brightnessMode == 2) {
                        Slog.w(LightsService.TAG, "setBrightness with LOW_PERSISTENCE unexpected #" + this.mId + ": brightness=0x" + Integer.toHexString(brightness));
                        return;
                    }
                    int mPowerState = SystemProperties.getInt("sys.xiaopeng.power_state", 0);
                    int screenBrightness = 0;
                    try {
                        screenBrightness = Settings.System.getInt(LightsService.this.getContext().getContentResolver(), "screen_brightness", 0);
                    } catch (Exception e) {
                        Slog.w(LightsService.TAG, e.getMessage());
                    }
                    if (mPowerState != 0 && brightness != 0) {
                        Slog.w(LightsService.TAG, "While screen turns off, setBrightness #" + this.mId + ": brightness=0x" + Integer.toHexString(brightness));
                        brightness = 0;
                    }
                    if (brightness == 0 || screenBrightness == 0 || brightness == DisplayManager.getOverrideBrightness(screenBrightness)) {
                        Slog.i(LightsService.TAG, "XP-POWER: setBrightness: brightness=0x" + Integer.toHexString(brightness) + ", power_state=" + mPowerState + ", provider brightness: " + screenBrightness);
                    }
                    int color = brightness & 255;
                    setLightLocked(color | (-16777216) | (color << 16) | (color << 8), 0, 0, 0, brightnessMode);
                } catch (Throwable th) {
                    throw th;
                }
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
        for (int i = 0; i < 8; i++) {
            this.mLights[i] = new LightImpl(i);
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
}
