package com.android.server;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Slog;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.job.controllers.JobStatus;
/* loaded from: classes.dex */
public class AnyMotionDetector {
    private static final long ACCELEROMETER_DATA_TIMEOUT_MILLIS = 3000;
    private static final boolean DEBUG = false;
    private static final long ORIENTATION_MEASUREMENT_DURATION_MILLIS = 2500;
    private static final long ORIENTATION_MEASUREMENT_INTERVAL_MILLIS = 5000;
    public static final int RESULT_MOVED = 1;
    public static final int RESULT_STATIONARY = 0;
    public static final int RESULT_UNKNOWN = -1;
    private static final int SAMPLING_INTERVAL_MILLIS = 40;
    private static final int STALE_MEASUREMENT_TIMEOUT_MILLIS = 120000;
    private static final int STATE_ACTIVE = 1;
    private static final int STATE_INACTIVE = 0;
    private static final String TAG = "AnyMotionDetector";
    private static final long WAKELOCK_TIMEOUT_MILLIS = 30000;
    private Sensor mAccelSensor;
    private DeviceIdleCallback mCallback;
    private final Handler mHandler;
    private boolean mMeasurementInProgress;
    private boolean mMeasurementTimeoutIsActive;
    private int mNumSufficientSamples;
    private RunningSignalStats mRunningStats;
    private SensorManager mSensorManager;
    private boolean mSensorRestartIsActive;
    private int mState;
    private final float mThresholdAngle;
    private PowerManager.WakeLock mWakeLock;
    private boolean mWakelockTimeoutIsActive;
    private final float THRESHOLD_ENERGY = 5.0f;
    private final Object mLock = new Object();
    private Vector3 mCurrentGravityVector = null;
    private Vector3 mPreviousGravityVector = null;
    private final SensorEventListener mListener = new SensorEventListener() { // from class: com.android.server.AnyMotionDetector.1
        @Override // android.hardware.SensorEventListener
        public void onSensorChanged(SensorEvent event) {
            int status = -1;
            synchronized (AnyMotionDetector.this.mLock) {
                Vector3 accelDatum = new Vector3(SystemClock.elapsedRealtime(), event.values[0], event.values[1], event.values[2]);
                AnyMotionDetector.this.mRunningStats.accumulate(accelDatum);
                if (AnyMotionDetector.this.mRunningStats.getSampleCount() >= AnyMotionDetector.this.mNumSufficientSamples) {
                    status = AnyMotionDetector.this.stopOrientationMeasurementLocked();
                }
            }
            if (status != -1) {
                AnyMotionDetector.this.mHandler.removeCallbacks(AnyMotionDetector.this.mWakelockTimeout);
                AnyMotionDetector.this.mWakelockTimeoutIsActive = false;
                AnyMotionDetector.this.mCallback.onAnyMotionResult(status);
            }
        }

        @Override // android.hardware.SensorEventListener
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };
    private final Runnable mSensorRestart = new Runnable() { // from class: com.android.server.AnyMotionDetector.2
        @Override // java.lang.Runnable
        public void run() {
            synchronized (AnyMotionDetector.this.mLock) {
                if (AnyMotionDetector.this.mSensorRestartIsActive) {
                    AnyMotionDetector.this.mSensorRestartIsActive = false;
                    AnyMotionDetector.this.startOrientationMeasurementLocked();
                }
            }
        }
    };
    private final Runnable mMeasurementTimeout = new Runnable() { // from class: com.android.server.AnyMotionDetector.3
        @Override // java.lang.Runnable
        public void run() {
            synchronized (AnyMotionDetector.this.mLock) {
                if (AnyMotionDetector.this.mMeasurementTimeoutIsActive) {
                    AnyMotionDetector.this.mMeasurementTimeoutIsActive = false;
                    int status = AnyMotionDetector.this.stopOrientationMeasurementLocked();
                    if (status != -1) {
                        AnyMotionDetector.this.mHandler.removeCallbacks(AnyMotionDetector.this.mWakelockTimeout);
                        AnyMotionDetector.this.mWakelockTimeoutIsActive = false;
                        AnyMotionDetector.this.mCallback.onAnyMotionResult(status);
                    }
                }
            }
        }
    };
    private final Runnable mWakelockTimeout = new Runnable() { // from class: com.android.server.AnyMotionDetector.4
        @Override // java.lang.Runnable
        public void run() {
            synchronized (AnyMotionDetector.this.mLock) {
                if (AnyMotionDetector.this.mWakelockTimeoutIsActive) {
                    AnyMotionDetector.this.mWakelockTimeoutIsActive = false;
                    AnyMotionDetector.this.stop();
                }
            }
        }
    };

    /* loaded from: classes.dex */
    interface DeviceIdleCallback {
        void onAnyMotionResult(int i);
    }

    public AnyMotionDetector(PowerManager pm, Handler handler, SensorManager sm, DeviceIdleCallback callback, float thresholdAngle) {
        this.mCallback = null;
        synchronized (this.mLock) {
            this.mWakeLock = pm.newWakeLock(1, TAG);
            this.mWakeLock.setReferenceCounted(false);
            this.mHandler = handler;
            this.mSensorManager = sm;
            this.mAccelSensor = this.mSensorManager.getDefaultSensor(1);
            this.mMeasurementInProgress = false;
            this.mMeasurementTimeoutIsActive = false;
            this.mWakelockTimeoutIsActive = false;
            this.mSensorRestartIsActive = false;
            this.mState = 0;
            this.mCallback = callback;
            this.mThresholdAngle = thresholdAngle;
            this.mRunningStats = new RunningSignalStats();
            this.mNumSufficientSamples = (int) Math.ceil(62.5d);
        }
    }

    public void checkForAnyMotion() {
        if (this.mState != 1) {
            synchronized (this.mLock) {
                this.mState = 1;
                this.mCurrentGravityVector = null;
                this.mPreviousGravityVector = null;
                this.mWakeLock.acquire();
                Message wakelockTimeoutMsg = Message.obtain(this.mHandler, this.mWakelockTimeout);
                this.mHandler.sendMessageDelayed(wakelockTimeoutMsg, 30000L);
                this.mWakelockTimeoutIsActive = true;
                startOrientationMeasurementLocked();
            }
        }
    }

    public void stop() {
        synchronized (this.mLock) {
            if (this.mState == 1) {
                this.mState = 0;
            }
            this.mHandler.removeCallbacks(this.mMeasurementTimeout);
            this.mHandler.removeCallbacks(this.mSensorRestart);
            this.mMeasurementTimeoutIsActive = false;
            this.mSensorRestartIsActive = false;
            if (this.mMeasurementInProgress) {
                this.mMeasurementInProgress = false;
                this.mSensorManager.unregisterListener(this.mListener);
            }
            this.mCurrentGravityVector = null;
            this.mPreviousGravityVector = null;
            if (this.mWakeLock.isHeld()) {
                this.mHandler.removeCallbacks(this.mWakelockTimeout);
                this.mWakelockTimeoutIsActive = false;
                this.mWakeLock.release();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startOrientationMeasurementLocked() {
        if (!this.mMeasurementInProgress && this.mAccelSensor != null) {
            if (this.mSensorManager.registerListener(this.mListener, this.mAccelSensor, EventLogTags.VOLUME_CHANGED)) {
                this.mMeasurementInProgress = true;
                this.mRunningStats.reset();
            }
            Message measurementTimeoutMsg = Message.obtain(this.mHandler, this.mMeasurementTimeout);
            this.mHandler.sendMessageDelayed(measurementTimeoutMsg, ACCELEROMETER_DATA_TIMEOUT_MILLIS);
            this.mMeasurementTimeoutIsActive = true;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int stopOrientationMeasurementLocked() {
        int status = -1;
        if (this.mMeasurementInProgress) {
            this.mHandler.removeCallbacks(this.mMeasurementTimeout);
            this.mMeasurementTimeoutIsActive = false;
            this.mSensorManager.unregisterListener(this.mListener);
            this.mMeasurementInProgress = false;
            this.mPreviousGravityVector = this.mCurrentGravityVector;
            this.mCurrentGravityVector = this.mRunningStats.getRunningAverage();
            if (this.mRunningStats.getSampleCount() == 0) {
                Slog.w(TAG, "No accelerometer data acquired for orientation measurement.");
            }
            this.mRunningStats.reset();
            status = getStationaryStatus();
            if (status != -1) {
                if (this.mWakeLock.isHeld()) {
                    this.mHandler.removeCallbacks(this.mWakelockTimeout);
                    this.mWakelockTimeoutIsActive = false;
                    this.mWakeLock.release();
                }
                this.mState = 0;
            } else {
                Message msg = Message.obtain(this.mHandler, this.mSensorRestart);
                this.mHandler.sendMessageDelayed(msg, ORIENTATION_MEASUREMENT_INTERVAL_MILLIS);
                this.mSensorRestartIsActive = true;
            }
        }
        return status;
    }

    public int getStationaryStatus() {
        if (this.mPreviousGravityVector == null || this.mCurrentGravityVector == null) {
            return -1;
        }
        Vector3 previousGravityVectorNormalized = this.mPreviousGravityVector.normalized();
        Vector3 currentGravityVectorNormalized = this.mCurrentGravityVector.normalized();
        float angle = previousGravityVectorNormalized.angleBetween(currentGravityVectorNormalized);
        if (angle < this.mThresholdAngle && this.mRunningStats.getEnergy() < 5.0f) {
            return 0;
        }
        if (Float.isNaN(angle)) {
            return 1;
        }
        long diffTime = this.mCurrentGravityVector.timeMillisSinceBoot - this.mPreviousGravityVector.timeMillisSinceBoot;
        return diffTime > JobStatus.DEFAULT_TRIGGER_MAX_DELAY ? -1 : 1;
    }

    /* loaded from: classes.dex */
    public static final class Vector3 {
        public long timeMillisSinceBoot;
        public float x;
        public float y;
        public float z;

        public Vector3(long timeMillisSinceBoot, float x, float y, float z) {
            this.timeMillisSinceBoot = timeMillisSinceBoot;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public float norm() {
            return (float) Math.sqrt(dotProduct(this));
        }

        public Vector3 normalized() {
            float mag = norm();
            return new Vector3(this.timeMillisSinceBoot, this.x / mag, this.y / mag, this.z / mag);
        }

        public float angleBetween(Vector3 other) {
            Vector3 crossVector = cross(other);
            float degrees = Math.abs((float) Math.toDegrees(Math.atan2(crossVector.norm(), dotProduct(other))));
            Slog.d(AnyMotionDetector.TAG, "angleBetween: this = " + toString() + ", other = " + other.toString() + ", degrees = " + degrees);
            return degrees;
        }

        public Vector3 cross(Vector3 v) {
            return new Vector3(v.timeMillisSinceBoot, (this.y * v.z) - (this.z * v.y), (this.z * v.x) - (this.x * v.z), (this.x * v.y) - (this.y * v.x));
        }

        public String toString() {
            String msg = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS + "timeMillisSinceBoot=" + this.timeMillisSinceBoot;
            return ((msg + " | x=" + this.x) + ", y=" + this.y) + ", z=" + this.z;
        }

        public float dotProduct(Vector3 v) {
            return (this.x * v.x) + (this.y * v.y) + (this.z * v.z);
        }

        public Vector3 times(float val) {
            return new Vector3(this.timeMillisSinceBoot, this.x * val, this.y * val, this.z * val);
        }

        public Vector3 plus(Vector3 v) {
            return new Vector3(v.timeMillisSinceBoot, v.x + this.x, v.y + this.y, v.z + this.z);
        }

        public Vector3 minus(Vector3 v) {
            return new Vector3(v.timeMillisSinceBoot, this.x - v.x, this.y - v.y, this.z - v.z);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class RunningSignalStats {
        Vector3 currentVector;
        float energy;
        Vector3 previousVector;
        Vector3 runningSum;
        int sampleCount;

        public RunningSignalStats() {
            reset();
        }

        public void reset() {
            this.previousVector = null;
            this.currentVector = null;
            this.runningSum = new Vector3(0L, 0.0f, 0.0f, 0.0f);
            this.energy = 0.0f;
            this.sampleCount = 0;
        }

        public void accumulate(Vector3 v) {
            if (v == null) {
                return;
            }
            this.sampleCount++;
            this.runningSum = this.runningSum.plus(v);
            this.previousVector = this.currentVector;
            this.currentVector = v;
            if (this.previousVector != null) {
                Vector3 dv = this.currentVector.minus(this.previousVector);
                float incrementalEnergy = (dv.x * dv.x) + (dv.y * dv.y) + (dv.z * dv.z);
                this.energy += incrementalEnergy;
            }
        }

        public Vector3 getRunningAverage() {
            if (this.sampleCount > 0) {
                return this.runningSum.times(1.0f / this.sampleCount);
            }
            return null;
        }

        public float getEnergy() {
            return this.energy;
        }

        public int getSampleCount() {
            return this.sampleCount;
        }

        public String toString() {
            String currentVectorString = this.currentVector == null ? "null" : this.currentVector.toString();
            String previousVectorString = this.previousVector == null ? "null" : this.previousVector.toString();
            String msg = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS + "previousVector = " + previousVectorString;
            return ((msg + ", currentVector = " + currentVectorString) + ", sampleCount = " + this.sampleCount) + ", energy = " + this.energy;
        }
    }
}
