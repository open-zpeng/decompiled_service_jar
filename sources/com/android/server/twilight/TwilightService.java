package com.android.server.twilight;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.icu.impl.CalendarAstronomer;
import android.icu.util.Calendar;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;
import com.xiaopeng.util.xpLogger;
import java.util.Objects;
/* loaded from: classes.dex */
public final class TwilightService extends SystemService implements AlarmManager.OnAlarmListener, Handler.Callback, LocationListener {
    private static final boolean DEBUG = true;
    private static final long LOCATION_REFRESH_INTERVAL = 7200000;
    private static final long LOCATION_REQUEST_INTERVAL = 300000;
    private static final int MSG_START_LISTENING = 1;
    private static final int MSG_STOP_LISTENING = 2;
    private static final String PROP_THEME_LATITUDE = "persist.sys.theme.latitude";
    private static final String PROP_THEME_LONGITUDE = "persist.sys.theme.longitude";
    private static final String TAG = "TwilightService";
    private static final double gLatitude = 31.40527d;
    private static final double gLongitude = 121.48941d;
    protected AlarmManager mAlarmManager;
    private boolean mBootCompleted;
    private final Handler mHandler;
    private boolean mHasListeners;
    protected Location mLastLocation;
    @GuardedBy("mListeners")
    protected TwilightState mLastTwilightState;
    @GuardedBy("mListeners")
    private final ArrayMap<TwilightListener, Handler> mListeners;
    private LocationManager mLocationManager;
    private Runnable mLocationRunnable;
    private boolean mLocationUpdated;
    private BroadcastReceiver mTimeChangedReceiver;

    public TwilightService(Context context) {
        super(context);
        this.mListeners = new ArrayMap<>();
        this.mLocationUpdated = false;
        this.mLocationRunnable = new Runnable() { // from class: com.android.server.twilight.TwilightService.2
            @Override // java.lang.Runnable
            public void run() {
                TwilightService.this.requestLocationIfNeeded();
            }
        };
        this.mHandler = new Handler(Looper.getMainLooper(), this);
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishLocalService(TwilightManager.class, new TwilightManager() { // from class: com.android.server.twilight.TwilightService.1
            @Override // com.android.server.twilight.TwilightManager
            public void registerListener(TwilightListener listener, Handler handler) {
                synchronized (TwilightService.this.mListeners) {
                    boolean wasEmpty = TwilightService.this.mListeners.isEmpty();
                    TwilightService.this.mListeners.put(listener, handler);
                    if (wasEmpty && !TwilightService.this.mListeners.isEmpty()) {
                        TwilightService.this.mHandler.sendEmptyMessage(1);
                    }
                }
            }

            @Override // com.android.server.twilight.TwilightManager
            public void unregisterListener(TwilightListener listener) {
                synchronized (TwilightService.this.mListeners) {
                    boolean wasEmpty = TwilightService.this.mListeners.isEmpty();
                    TwilightService.this.mListeners.remove(listener);
                    if (!wasEmpty && TwilightService.this.mListeners.isEmpty()) {
                        TwilightService.this.mHandler.sendEmptyMessage(2);
                    }
                }
            }

            @Override // com.android.server.twilight.TwilightManager
            public TwilightState getLastTwilightState() {
                TwilightState twilightState;
                synchronized (TwilightService.this.mListeners) {
                    twilightState = TwilightService.this.mLastTwilightState;
                }
                return twilightState;
            }
        });
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 1000) {
            Context c = getContext();
            this.mAlarmManager = (AlarmManager) c.getSystemService("alarm");
            this.mLocationManager = (LocationManager) c.getSystemService("location");
            this.mBootCompleted = true;
            if (this.mHasListeners) {
                startListening();
            }
        }
    }

    @Override // android.os.Handler.Callback
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case 1:
                if (!this.mHasListeners) {
                    this.mHasListeners = true;
                    if (this.mBootCompleted) {
                        startListening();
                    }
                }
                return true;
            case 2:
                if (this.mHasListeners) {
                    this.mHasListeners = false;
                    if (this.mBootCompleted) {
                        stopListening();
                    }
                }
                return true;
            default:
                return false;
        }
    }

    private Location getOrCreateLocation(Location location) {
        if (location == null) {
            double latitude = -1.0d;
            double longitude = -1.0d;
            try {
                latitude = Double.valueOf(SystemProperties.get(PROP_THEME_LATITUDE)).doubleValue();
                longitude = Double.valueOf(SystemProperties.get(PROP_THEME_LONGITUDE)).doubleValue();
            } catch (Exception e) {
                Slog.d(TAG, "getOrCreateLocation valueOf e=" + e);
            }
            double latitude2 = latitude != -1.0d ? latitude : gLatitude;
            double longitude2 = longitude != -1.0d ? longitude : gLongitude;
            location = new Location("theme");
            location.setLatitude(latitude2);
            location.setLongitude(longitude2);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("getOrCreateLocation location =");
        sb.append(location != null ? location.toString() : "null");
        xpLogger.slog(TAG, sb.toString());
        return location;
    }

    private void setLocationProperties(double latitude, double longitude) {
        SystemProperties.set(PROP_THEME_LATITUDE, String.valueOf(latitude));
        SystemProperties.set(PROP_THEME_LONGITUDE, String.valueOf(longitude));
        xpLogger.slog(TAG, "setLocationProperties latitude = " + latitude + " longitude = " + longitude);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void requestLocationIfNeeded() {
        xpLogger.slog(TAG, "requestLocationIfNeeded locationUpdated=" + this.mLocationUpdated);
        long interval = this.mLocationUpdated ? 7200000L : 300000L;
        this.mLocationManager.requestSingleUpdate("gps", this, Looper.getMainLooper());
        this.mHandler.removeCallbacks(this.mLocationRunnable);
        this.mHandler.postDelayed(this.mLocationRunnable, interval);
    }

    private void onTwilightAlarmed() {
        this.mLocationUpdated = false;
        requestLocationIfNeeded();
    }

    private void startListening() {
        Slog.d(TAG, "startListening");
        this.mLocationManager.requestLocationUpdates((LocationRequest) null, this, Looper.getMainLooper());
        if (this.mLocationManager.getLastLocation() == null) {
            if (this.mLocationManager.isProviderEnabled("network")) {
                this.mLocationManager.requestSingleUpdate("network", this, Looper.getMainLooper());
            } else if (this.mLocationManager.isProviderEnabled("gps")) {
                this.mLocationManager.requestSingleUpdate("gps", this, Looper.getMainLooper());
            }
        }
        requestLocationIfNeeded();
        this.mLastLocation = getOrCreateLocation(this.mLastLocation);
        if (this.mTimeChangedReceiver == null) {
            this.mTimeChangedReceiver = new BroadcastReceiver() { // from class: com.android.server.twilight.TwilightService.3
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context, Intent intent) {
                    Slog.d(TwilightService.TAG, "onReceive: " + intent);
                    TwilightService.this.updateTwilightState();
                }
            };
            IntentFilter intentFilter = new IntentFilter("android.intent.action.TIME_SET");
            intentFilter.addAction("android.intent.action.TIMEZONE_CHANGED");
            getContext().registerReceiver(this.mTimeChangedReceiver, intentFilter);
        }
        updateTwilightState();
    }

    private void stopListening() {
        Slog.d(TAG, "stopListening");
        if (this.mTimeChangedReceiver != null) {
            getContext().unregisterReceiver(this.mTimeChangedReceiver);
            this.mTimeChangedReceiver = null;
        }
        if (this.mLastTwilightState != null) {
            this.mAlarmManager.cancel(this);
        }
        this.mLocationManager.removeUpdates(this);
        this.mLastLocation = null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateTwilightState() {
        long currentTimeMillis = System.currentTimeMillis();
        Location location = this.mLastLocation != null ? this.mLastLocation : this.mLocationManager.getLastLocation();
        final TwilightState state = calculateTwilightState(location, currentTimeMillis);
        Slog.d(TAG, "updateTwilightState: " + state);
        synchronized (this.mListeners) {
            if (!Objects.equals(this.mLastTwilightState, state)) {
                this.mLastTwilightState = state;
                for (int i = this.mListeners.size() - 1; i >= 0; i--) {
                    final TwilightListener listener = this.mListeners.keyAt(i);
                    Handler handler = this.mListeners.valueAt(i);
                    handler.post(new Runnable() { // from class: com.android.server.twilight.TwilightService.4
                        @Override // java.lang.Runnable
                        public void run() {
                            listener.onTwilightStateChanged(state);
                        }
                    });
                }
            }
        }
        if (state != null) {
            long triggerAtMillis = state.isNight() ? state.sunriseTimeMillis() : state.sunsetTimeMillis();
            this.mAlarmManager.setExact(1, triggerAtMillis, TAG, this, this.mHandler);
        }
    }

    @Override // android.app.AlarmManager.OnAlarmListener
    public void onAlarm() {
        Slog.d(TAG, "onAlarm");
        onTwilightAlarmed();
        updateTwilightState();
    }

    @Override // android.location.LocationListener
    public void onLocationChanged(Location location) {
        if (location != null) {
            if (location.getLongitude() != 0.0d || location.getLatitude() != 0.0d) {
                Slog.d(TAG, "onLocationChanged: provider=" + location.getProvider() + " accuracy=" + location.getAccuracy() + " time=" + location.getTime());
                this.mLocationUpdated = true;
                setLocationProperties(location.getLatitude(), location.getLongitude());
                this.mLastLocation = location;
                updateTwilightState();
            }
        }
    }

    @Override // android.location.LocationListener
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override // android.location.LocationListener
    public void onProviderEnabled(String provider) {
    }

    @Override // android.location.LocationListener
    public void onProviderDisabled(String provider) {
    }

    private static TwilightState calculateTwilightState(Location location, long timeMillis) {
        if (location == null) {
            return null;
        }
        CalendarAstronomer ca = new CalendarAstronomer(location.getLongitude(), location.getLatitude());
        Calendar noon = Calendar.getInstance();
        noon.setTimeInMillis(timeMillis);
        noon.set(11, 12);
        noon.set(12, 0);
        noon.set(13, 0);
        noon.set(14, 0);
        ca.setTime(noon.getTimeInMillis());
        long sunriseTimeMillis = ca.getSunRiseSet(true);
        long sunsetTimeMillis = ca.getSunRiseSet(false);
        if (sunsetTimeMillis < timeMillis) {
            noon.add(5, 1);
            ca.setTime(noon.getTimeInMillis());
            sunriseTimeMillis = ca.getSunRiseSet(true);
        } else if (sunriseTimeMillis > timeMillis) {
            noon.add(5, -1);
            ca.setTime(noon.getTimeInMillis());
            sunsetTimeMillis = ca.getSunRiseSet(false);
        }
        return new TwilightState(sunriseTimeMillis, sunsetTimeMillis);
    }
}
