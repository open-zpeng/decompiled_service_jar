package com.android.server.display.whitebalance;

import android.util.Slog;
import android.util.Spline;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.display.color.ColorDisplayService;
import com.android.server.display.utils.History;
import com.android.server.display.whitebalance.AmbientSensor;
import java.io.PrintWriter;

/* loaded from: classes.dex */
public class DisplayWhiteBalanceController implements AmbientSensor.AmbientBrightnessSensor.Callbacks, AmbientSensor.AmbientColorTemperatureSensor.Callbacks {
    private static final int HISTORY_SIZE = 50;
    protected static final String TAG = "DisplayWhiteBalanceController";
    private float mAmbientColorTemperature;
    private History mAmbientColorTemperatureHistory;
    private float mAmbientColorTemperatureOverride;
    private Spline.LinearSpline mAmbientToDisplayColorTemperatureSpline;
    @VisibleForTesting
    AmbientFilter mBrightnessFilter;
    private AmbientSensor.AmbientBrightnessSensor mBrightnessSensor;
    private Callbacks mCallbacks;
    private ColorDisplayService.ColorDisplayServiceInternal mColorDisplayServiceInternal;
    @VisibleForTesting
    AmbientFilter mColorTemperatureFilter;
    private AmbientSensor.AmbientColorTemperatureSensor mColorTemperatureSensor;
    private boolean mEnabled;
    private Spline.LinearSpline mHighLightAmbientBrightnessToBiasSpline;
    private final float mHighLightAmbientColorTemperature;
    private float mLastAmbientColorTemperature;
    private float mLatestAmbientBrightness;
    private float mLatestAmbientColorTemperature;
    private float mLatestHighLightBias;
    private float mLatestLowLightBias;
    protected boolean mLoggingEnabled;
    private Spline.LinearSpline mLowLightAmbientBrightnessToBiasSpline;
    private final float mLowLightAmbientColorTemperature;
    @VisibleForTesting
    float mPendingAmbientColorTemperature;
    private DisplayWhiteBalanceThrottler mThrottler;

    /* loaded from: classes.dex */
    public interface Callbacks {
        void updateWhiteBalance();
    }

    /* JADX WARN: Can't wrap try/catch for region: R(24:1|2|3|4|(2:5|6)|7|(1:13)|14|15|16|17|18|19|(1:25)|26|(1:32)|33|34|35|36|37|38|39|(1:(0))) */
    /* JADX WARN: Code restructure failed: missing block: B:23:0x0088, code lost:
        r0 = e;
     */
    /* JADX WARN: Code restructure failed: missing block: B:25:0x008a, code lost:
        r0 = e;
     */
    /* JADX WARN: Code restructure failed: missing block: B:27:0x008d, code lost:
        android.util.Slog.e(com.android.server.display.whitebalance.DisplayWhiteBalanceController.TAG, "failed to create high light ambient brightness to bias spline.", r0);
        r16.mHighLightAmbientBrightnessToBiasSpline = null;
     */
    /* JADX WARN: Code restructure failed: missing block: B:46:0x00e1, code lost:
        r0 = e;
     */
    /* JADX WARN: Code restructure failed: missing block: B:48:0x00e3, code lost:
        r0 = e;
     */
    /* JADX WARN: Code restructure failed: missing block: B:50:0x00e8, code lost:
        android.util.Slog.e(com.android.server.display.whitebalance.DisplayWhiteBalanceController.TAG, "failed to create ambient to display color temperature spline.", r0);
        r16.mAmbientToDisplayColorTemperatureSpline = null;
     */
    /* JADX WARN: Removed duplicated region for block: B:30:0x0099  */
    /* JADX WARN: Removed duplicated region for block: B:37:0x00bb  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public DisplayWhiteBalanceController(com.android.server.display.whitebalance.AmbientSensor.AmbientBrightnessSensor r17, com.android.server.display.whitebalance.AmbientFilter r18, com.android.server.display.whitebalance.AmbientSensor.AmbientColorTemperatureSensor r19, com.android.server.display.whitebalance.AmbientFilter r20, com.android.server.display.whitebalance.DisplayWhiteBalanceThrottler r21, float[] r22, float[] r23, float r24, float[] r25, float[] r26, float r27, float[] r28, float[] r29) {
        /*
            Method dump skipped, instructions count: 251
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.display.whitebalance.DisplayWhiteBalanceController.<init>(com.android.server.display.whitebalance.AmbientSensor$AmbientBrightnessSensor, com.android.server.display.whitebalance.AmbientFilter, com.android.server.display.whitebalance.AmbientSensor$AmbientColorTemperatureSensor, com.android.server.display.whitebalance.AmbientFilter, com.android.server.display.whitebalance.DisplayWhiteBalanceThrottler, float[], float[], float, float[], float[], float, float[], float[]):void");
    }

    public boolean setEnabled(boolean enabled) {
        if (enabled) {
            return enable();
        }
        return disable();
    }

    public boolean setCallbacks(Callbacks callbacks) {
        if (this.mCallbacks == callbacks) {
            return false;
        }
        this.mCallbacks = callbacks;
        return true;
    }

    public boolean setLoggingEnabled(boolean loggingEnabled) {
        if (this.mLoggingEnabled == loggingEnabled) {
            return false;
        }
        this.mLoggingEnabled = loggingEnabled;
        this.mBrightnessSensor.setLoggingEnabled(loggingEnabled);
        this.mBrightnessFilter.setLoggingEnabled(loggingEnabled);
        this.mColorTemperatureSensor.setLoggingEnabled(loggingEnabled);
        this.mColorTemperatureFilter.setLoggingEnabled(loggingEnabled);
        this.mThrottler.setLoggingEnabled(loggingEnabled);
        return true;
    }

    public boolean setAmbientColorTemperatureOverride(float ambientColorTemperatureOverride) {
        if (this.mAmbientColorTemperatureOverride == ambientColorTemperatureOverride) {
            return false;
        }
        this.mAmbientColorTemperatureOverride = ambientColorTemperatureOverride;
        return true;
    }

    public void dump(PrintWriter writer) {
        writer.println(TAG);
        writer.println("  mLoggingEnabled=" + this.mLoggingEnabled);
        writer.println("  mEnabled=" + this.mEnabled);
        writer.println("  mCallbacks=" + this.mCallbacks);
        this.mBrightnessSensor.dump(writer);
        this.mBrightnessFilter.dump(writer);
        this.mColorTemperatureSensor.dump(writer);
        this.mColorTemperatureFilter.dump(writer);
        this.mThrottler.dump(writer);
        writer.println("  mLowLightAmbientColorTemperature=" + this.mLowLightAmbientColorTemperature);
        writer.println("  mHighLightAmbientColorTemperature=" + this.mHighLightAmbientColorTemperature);
        writer.println("  mAmbientColorTemperature=" + this.mAmbientColorTemperature);
        writer.println("  mPendingAmbientColorTemperature=" + this.mPendingAmbientColorTemperature);
        writer.println("  mLastAmbientColorTemperature=" + this.mLastAmbientColorTemperature);
        writer.println("  mAmbientColorTemperatureHistory=" + this.mAmbientColorTemperatureHistory);
        writer.println("  mAmbientColorTemperatureOverride=" + this.mAmbientColorTemperatureOverride);
        writer.println("  mAmbientToDisplayColorTemperatureSpline=" + this.mAmbientToDisplayColorTemperatureSpline);
        writer.println("  mLowLightAmbientBrightnessToBiasSpline=" + this.mLowLightAmbientBrightnessToBiasSpline);
        writer.println("  mHighLightAmbientBrightnessToBiasSpline=" + this.mHighLightAmbientBrightnessToBiasSpline);
    }

    @Override // com.android.server.display.whitebalance.AmbientSensor.AmbientBrightnessSensor.Callbacks
    public void onAmbientBrightnessChanged(float value) {
        long time = System.currentTimeMillis();
        this.mBrightnessFilter.addValue(time, value);
        updateAmbientColorTemperature();
    }

    @Override // com.android.server.display.whitebalance.AmbientSensor.AmbientColorTemperatureSensor.Callbacks
    public void onAmbientColorTemperatureChanged(float value) {
        long time = System.currentTimeMillis();
        this.mColorTemperatureFilter.addValue(time, value);
        updateAmbientColorTemperature();
    }

    public void updateAmbientColorTemperature() {
        Spline.LinearSpline linearSpline;
        Spline.LinearSpline linearSpline2;
        long time = System.currentTimeMillis();
        float ambientColorTemperature = this.mColorTemperatureFilter.getEstimate(time);
        this.mLatestAmbientColorTemperature = ambientColorTemperature;
        Spline.LinearSpline linearSpline3 = this.mAmbientToDisplayColorTemperatureSpline;
        if (linearSpline3 != null && ambientColorTemperature != -1.0f) {
            ambientColorTemperature = linearSpline3.interpolate(ambientColorTemperature);
        }
        float ambientBrightness = this.mBrightnessFilter.getEstimate(time);
        this.mLatestAmbientBrightness = ambientBrightness;
        if (ambientColorTemperature != -1.0f && (linearSpline2 = this.mLowLightAmbientBrightnessToBiasSpline) != null) {
            float bias = linearSpline2.interpolate(ambientBrightness);
            ambientColorTemperature = (bias * ambientColorTemperature) + ((1.0f - bias) * this.mLowLightAmbientColorTemperature);
            this.mLatestLowLightBias = bias;
        }
        if (ambientColorTemperature != -1.0f && (linearSpline = this.mHighLightAmbientBrightnessToBiasSpline) != null) {
            float bias2 = linearSpline.interpolate(ambientBrightness);
            ambientColorTemperature = ((1.0f - bias2) * ambientColorTemperature) + (this.mHighLightAmbientColorTemperature * bias2);
            this.mLatestHighLightBias = bias2;
        }
        if (this.mAmbientColorTemperatureOverride != -1.0f) {
            if (this.mLoggingEnabled) {
                Slog.d(TAG, "override ambient color temperature: " + ambientColorTemperature + " => " + this.mAmbientColorTemperatureOverride);
            }
            ambientColorTemperature = this.mAmbientColorTemperatureOverride;
        }
        if (ambientColorTemperature == -1.0f || this.mThrottler.throttle(ambientColorTemperature)) {
            return;
        }
        if (this.mLoggingEnabled) {
            Slog.d(TAG, "pending ambient color temperature: " + ambientColorTemperature);
        }
        this.mPendingAmbientColorTemperature = ambientColorTemperature;
        Callbacks callbacks = this.mCallbacks;
        if (callbacks != null) {
            callbacks.updateWhiteBalance();
        }
    }

    public void updateDisplayColorTemperature() {
        float ambientColorTemperature = -1.0f;
        if (this.mAmbientColorTemperature == -1.0f && this.mPendingAmbientColorTemperature == -1.0f) {
            ambientColorTemperature = this.mLastAmbientColorTemperature;
        }
        float f = this.mPendingAmbientColorTemperature;
        if (f != -1.0f && f != this.mAmbientColorTemperature) {
            ambientColorTemperature = this.mPendingAmbientColorTemperature;
        }
        if (ambientColorTemperature == -1.0f) {
            return;
        }
        this.mAmbientColorTemperature = ambientColorTemperature;
        if (this.mLoggingEnabled) {
            Slog.d(TAG, "ambient color temperature: " + this.mAmbientColorTemperature);
        }
        this.mPendingAmbientColorTemperature = -1.0f;
        this.mAmbientColorTemperatureHistory.add(this.mAmbientColorTemperature);
        Slog.d(TAG, "Display cct: " + this.mAmbientColorTemperature + " Latest ambient cct: " + this.mLatestAmbientColorTemperature + " Latest ambient lux: " + this.mLatestAmbientBrightness + " Latest low light bias: " + this.mLatestLowLightBias + " Latest high light bias: " + this.mLatestHighLightBias);
        this.mColorDisplayServiceInternal.setDisplayWhiteBalanceColorTemperature((int) this.mAmbientColorTemperature);
        this.mLastAmbientColorTemperature = this.mAmbientColorTemperature;
    }

    private void validateArguments(AmbientSensor.AmbientBrightnessSensor brightnessSensor, AmbientFilter brightnessFilter, AmbientSensor.AmbientColorTemperatureSensor colorTemperatureSensor, AmbientFilter colorTemperatureFilter, DisplayWhiteBalanceThrottler throttler) {
        Preconditions.checkNotNull(brightnessSensor, "brightnessSensor must not be null");
        Preconditions.checkNotNull(brightnessFilter, "brightnessFilter must not be null");
        Preconditions.checkNotNull(colorTemperatureSensor, "colorTemperatureSensor must not be null");
        Preconditions.checkNotNull(colorTemperatureFilter, "colorTemperatureFilter must not be null");
        Preconditions.checkNotNull(throttler, "throttler cannot be null");
    }

    private boolean enable() {
        if (this.mEnabled) {
            return false;
        }
        if (this.mLoggingEnabled) {
            Slog.d(TAG, "enabling");
        }
        this.mEnabled = true;
        this.mBrightnessSensor.setEnabled(true);
        this.mColorTemperatureSensor.setEnabled(true);
        return true;
    }

    private boolean disable() {
        if (this.mEnabled) {
            if (this.mLoggingEnabled) {
                Slog.d(TAG, "disabling");
            }
            this.mEnabled = false;
            this.mBrightnessSensor.setEnabled(false);
            this.mBrightnessFilter.clear();
            this.mColorTemperatureSensor.setEnabled(false);
            this.mColorTemperatureFilter.clear();
            this.mThrottler.clear();
            this.mAmbientColorTemperature = -1.0f;
            this.mPendingAmbientColorTemperature = -1.0f;
            this.mColorDisplayServiceInternal.resetDisplayWhiteBalanceColorTemperature();
            return true;
        }
        return false;
    }
}
