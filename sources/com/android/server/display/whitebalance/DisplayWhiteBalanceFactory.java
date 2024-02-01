package com.android.server.display.whitebalance;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.hardware.SensorManager;
import android.os.Handler;
import android.util.TypedValue;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.whitebalance.AmbientFilter;
import com.android.server.display.whitebalance.AmbientSensor;

/* loaded from: classes.dex */
public class DisplayWhiteBalanceFactory {
    private static final String BRIGHTNESS_FILTER_TAG = "AmbientBrightnessFilter";
    private static final String COLOR_TEMPERATURE_FILTER_TAG = "AmbientColorTemperatureFilter";

    public static DisplayWhiteBalanceController create(Handler handler, SensorManager sensorManager, Resources resources) {
        AmbientSensor.AmbientBrightnessSensor brightnessSensor = createBrightnessSensor(handler, sensorManager, resources);
        AmbientFilter brightnessFilter = createBrightnessFilter(resources);
        AmbientSensor.AmbientColorTemperatureSensor colorTemperatureSensor = createColorTemperatureSensor(handler, sensorManager, resources);
        AmbientFilter colorTemperatureFilter = createColorTemperatureFilter(resources);
        DisplayWhiteBalanceThrottler throttler = createThrottler(resources);
        float[] displayWhiteBalanceLowLightAmbientBrightnesses = getFloatArray(resources, 17236025);
        float[] displayWhiteBalanceLowLightAmbientBiases = getFloatArray(resources, 17236024);
        float lowLightAmbientColorTemperature = getFloat(resources, 17105059);
        float[] displayWhiteBalanceHighLightAmbientBrightnesses = getFloatArray(resources, 17236022);
        float[] displayWhiteBalanceHighLightAmbientBiases = getFloatArray(resources, 17236021);
        float highLightAmbientColorTemperature = getFloat(resources, 17105058);
        float[] ambientColorTemperatures = getFloatArray(resources, 17236015);
        float[] displayColorTempeartures = getFloatArray(resources, 17236018);
        DisplayWhiteBalanceController controller = new DisplayWhiteBalanceController(brightnessSensor, brightnessFilter, colorTemperatureSensor, colorTemperatureFilter, throttler, displayWhiteBalanceLowLightAmbientBrightnesses, displayWhiteBalanceLowLightAmbientBiases, lowLightAmbientColorTemperature, displayWhiteBalanceHighLightAmbientBrightnesses, displayWhiteBalanceHighLightAmbientBiases, highLightAmbientColorTemperature, ambientColorTemperatures, displayColorTempeartures);
        brightnessSensor.setCallbacks(controller);
        colorTemperatureSensor.setCallbacks(controller);
        return controller;
    }

    private DisplayWhiteBalanceFactory() {
    }

    @VisibleForTesting
    public static AmbientSensor.AmbientBrightnessSensor createBrightnessSensor(Handler handler, SensorManager sensorManager, Resources resources) {
        int rate = resources.getInteger(17694788);
        return new AmbientSensor.AmbientBrightnessSensor(handler, sensorManager, rate);
    }

    public static AmbientFilter createBrightnessFilter(Resources resources) {
        int horizon = resources.getInteger(17694787);
        float intercept = getFloat(resources, 17105056);
        if (!Float.isNaN(intercept)) {
            return new AmbientFilter.WeightedMovingAverageAmbientFilter(BRIGHTNESS_FILTER_TAG, horizon, intercept);
        }
        throw new IllegalArgumentException("missing configurations: expected config_displayWhiteBalanceBrightnessFilterIntercept");
    }

    @VisibleForTesting
    public static AmbientSensor.AmbientColorTemperatureSensor createColorTemperatureSensor(Handler handler, SensorManager sensorManager, Resources resources) {
        String name = resources.getString(17039724);
        int rate = resources.getInteger(17694793);
        return new AmbientSensor.AmbientColorTemperatureSensor(handler, sensorManager, name, rate);
    }

    private static AmbientFilter createColorTemperatureFilter(Resources resources) {
        int horizon = resources.getInteger(17694790);
        float intercept = getFloat(resources, 17105057);
        if (!Float.isNaN(intercept)) {
            return new AmbientFilter.WeightedMovingAverageAmbientFilter(COLOR_TEMPERATURE_FILTER_TAG, horizon, intercept);
        }
        throw new IllegalArgumentException("missing configurations: expected config_displayWhiteBalanceColorTemperatureFilterIntercept");
    }

    private static DisplayWhiteBalanceThrottler createThrottler(Resources resources) {
        int increaseDebounce = resources.getInteger(17694794);
        int decreaseDebounce = resources.getInteger(17694795);
        float[] baseThresholds = getFloatArray(resources, 17236016);
        float[] increaseThresholds = getFloatArray(resources, 17236023);
        float[] decreaseThresholds = getFloatArray(resources, 17236017);
        return new DisplayWhiteBalanceThrottler(increaseDebounce, decreaseDebounce, baseThresholds, increaseThresholds, decreaseThresholds);
    }

    private static float getFloat(Resources resources, int id) {
        TypedValue value = new TypedValue();
        resources.getValue(id, value, true);
        if (value.type != 4) {
            return Float.NaN;
        }
        return value.getFloat();
    }

    private static float[] getFloatArray(Resources resources, int id) {
        TypedArray array = resources.obtainTypedArray(id);
        try {
            if (array.length() == 0) {
                return null;
            }
            float[] values = new float[array.length()];
            for (int i = 0; i < values.length; i++) {
                values[i] = array.getFloat(i, Float.NaN);
                if (Float.isNaN(values[i])) {
                    return null;
                }
            }
            return values;
        } finally {
            array.recycle();
        }
    }
}
