package com.android.server.location;

import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.location.Geofence;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Slog;
import com.android.server.LocationManagerService;
import com.android.server.PendingIntentUtils;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
/* loaded from: classes.dex */
public class GeofenceManager implements LocationListener, PendingIntent.OnFinished {
    private static final boolean D = LocationManagerService.D;
    private static final long DEFAULT_MIN_INTERVAL_MS = 1800000;
    private static final long MAX_AGE_NANOS = 300000000000L;
    private static final long MAX_INTERVAL_MS = 7200000;
    private static final int MAX_SPEED_M_S = 100;
    private static final int MSG_UPDATE_FENCES = 1;
    private static final String TAG = "GeofenceManager";
    private final AppOpsManager mAppOps;
    private final LocationBlacklist mBlacklist;
    private final Context mContext;
    private long mEffectiveMinIntervalMs;
    private final GeofenceHandler mHandler;
    private Location mLastLocationUpdate;
    private final LocationManager mLocationManager;
    private long mLocationUpdateInterval;
    private boolean mPendingUpdate;
    private boolean mReceivingLocationUpdates;
    private ContentResolver mResolver;
    private final PowerManager.WakeLock mWakeLock;
    private Object mLock = new Object();
    private List<GeofenceState> mFences = new LinkedList();

    public GeofenceManager(Context context, LocationBlacklist blacklist) {
        this.mContext = context;
        this.mLocationManager = (LocationManager) this.mContext.getSystemService("location");
        this.mAppOps = (AppOpsManager) this.mContext.getSystemService("appops");
        PowerManager powerManager = (PowerManager) this.mContext.getSystemService("power");
        this.mWakeLock = powerManager.newWakeLock(1, TAG);
        this.mHandler = new GeofenceHandler();
        this.mBlacklist = blacklist;
        this.mResolver = this.mContext.getContentResolver();
        updateMinInterval();
        this.mResolver.registerContentObserver(Settings.Global.getUriFor("location_background_throttle_proximity_alert_interval_ms"), true, new ContentObserver(this.mHandler) { // from class: com.android.server.location.GeofenceManager.1
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                synchronized (GeofenceManager.this.mLock) {
                    GeofenceManager.this.updateMinInterval();
                }
            }
        }, -1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateMinInterval() {
        this.mEffectiveMinIntervalMs = Settings.Global.getLong(this.mResolver, "location_background_throttle_proximity_alert_interval_ms", 1800000L);
    }

    public void addFence(LocationRequest request, Geofence geofence, PendingIntent intent, int allowedResolutionLevel, int uid, String packageName) {
        int i;
        String str;
        if (D) {
            StringBuilder sb = new StringBuilder();
            sb.append("addFence: request=");
            sb.append(request);
            sb.append(", geofence=");
            sb.append(geofence);
            sb.append(", intent=");
            sb.append(intent);
            sb.append(", uid=");
            i = uid;
            sb.append(i);
            sb.append(", packageName=");
            str = packageName;
            sb.append(str);
            Slog.d(TAG, sb.toString());
        } else {
            i = uid;
            str = packageName;
        }
        GeofenceState state = new GeofenceState(geofence, request.getExpireAt(), allowedResolutionLevel, i, str, intent);
        synchronized (this.mLock) {
            int i2 = this.mFences.size() - 1;
            while (true) {
                if (i2 < 0) {
                    break;
                }
                GeofenceState w = this.mFences.get(i2);
                if (!geofence.equals(w.mFence) || !intent.equals(w.mIntent)) {
                    i2--;
                } else {
                    this.mFences.remove(i2);
                    break;
                }
            }
            this.mFences.add(state);
            scheduleUpdateFencesLocked();
        }
    }

    public void removeFence(Geofence fence, PendingIntent intent) {
        if (D) {
            Slog.d(TAG, "removeFence: fence=" + fence + ", intent=" + intent);
        }
        synchronized (this.mLock) {
            Iterator<GeofenceState> iter = this.mFences.iterator();
            while (iter.hasNext()) {
                GeofenceState state = iter.next();
                if (state.mIntent.equals(intent)) {
                    if (fence == null) {
                        iter.remove();
                    } else if (fence.equals(state.mFence)) {
                        iter.remove();
                    }
                }
            }
            scheduleUpdateFencesLocked();
        }
    }

    public void removeFence(String packageName) {
        if (D) {
            Slog.d(TAG, "removeFence: packageName=" + packageName);
        }
        synchronized (this.mLock) {
            Iterator<GeofenceState> iter = this.mFences.iterator();
            while (iter.hasNext()) {
                GeofenceState state = iter.next();
                if (state.mPackageName.equals(packageName)) {
                    iter.remove();
                }
            }
            scheduleUpdateFencesLocked();
        }
    }

    private void removeExpiredFencesLocked() {
        long time = SystemClock.elapsedRealtime();
        Iterator<GeofenceState> iter = this.mFences.iterator();
        while (iter.hasNext()) {
            GeofenceState state = iter.next();
            if (state.mExpireAt < time) {
                iter.remove();
            }
        }
    }

    private void scheduleUpdateFencesLocked() {
        if (!this.mPendingUpdate) {
            this.mPendingUpdate = true;
            this.mHandler.sendEmptyMessage(1);
        }
    }

    private Location getFreshLocationLocked() {
        Location location = this.mReceivingLocationUpdates ? this.mLastLocationUpdate : null;
        if (location == null && !this.mFences.isEmpty()) {
            location = this.mLocationManager.getLastLocation();
        }
        if (location == null) {
            return null;
        }
        long now = SystemClock.elapsedRealtimeNanos();
        if (now - location.getElapsedRealtimeNanos() > MAX_AGE_NANOS) {
            return null;
        }
        return location;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateFences() {
        long intervalMs;
        List<PendingIntent> enterIntents = new LinkedList<>();
        List<PendingIntent> exitIntents = new LinkedList<>();
        synchronized (this.mLock) {
            this.mPendingUpdate = false;
            removeExpiredFencesLocked();
            Location location = getFreshLocationLocked();
            double minFenceDistance = Double.MAX_VALUE;
            boolean needUpdates = false;
            for (GeofenceState state : this.mFences) {
                if (this.mBlacklist.isBlacklisted(state.mPackageName)) {
                    if (D) {
                        Slog.d(TAG, "skipping geofence processing for blacklisted app: " + state.mPackageName);
                    }
                } else {
                    int op = LocationManagerService.resolutionLevelToOp(state.mAllowedResolutionLevel);
                    if (op >= 0 && this.mAppOps.noteOpNoThrow(1, state.mUid, state.mPackageName) != 0) {
                        if (D) {
                            Slog.d(TAG, "skipping geofence processing for no op app: " + state.mPackageName);
                        }
                    } else {
                        needUpdates = true;
                        if (location != null) {
                            int event = state.processLocation(location);
                            if ((event & 1) != 0) {
                                enterIntents.add(state.mIntent);
                            }
                            if ((event & 2) != 0) {
                                exitIntents.add(state.mIntent);
                            }
                            double fenceDistance = state.getDistanceToBoundary();
                            if (fenceDistance < minFenceDistance) {
                                minFenceDistance = fenceDistance;
                            }
                        }
                    }
                }
            }
            if (needUpdates) {
                if (location != null && Double.compare(minFenceDistance, Double.MAX_VALUE) != 0) {
                    intervalMs = (long) Math.min(7200000.0d, Math.max(this.mEffectiveMinIntervalMs, (1000.0d * minFenceDistance) / 100.0d));
                } else {
                    intervalMs = this.mEffectiveMinIntervalMs;
                }
                if (!this.mReceivingLocationUpdates || this.mLocationUpdateInterval != intervalMs) {
                    this.mReceivingLocationUpdates = true;
                    this.mLocationUpdateInterval = intervalMs;
                    this.mLastLocationUpdate = location;
                    LocationRequest request = new LocationRequest();
                    request.setInterval(intervalMs).setFastestInterval(0L);
                    this.mLocationManager.requestLocationUpdates(request, this, this.mHandler.getLooper());
                }
            } else if (this.mReceivingLocationUpdates) {
                this.mReceivingLocationUpdates = false;
                this.mLocationUpdateInterval = 0L;
                this.mLastLocationUpdate = null;
                this.mLocationManager.removeUpdates(this);
            }
            if (D) {
                Slog.d(TAG, "updateFences: location=" + location + ", mFences.size()=" + this.mFences.size() + ", mReceivingLocationUpdates=" + this.mReceivingLocationUpdates + ", mLocationUpdateInterval=" + this.mLocationUpdateInterval + ", mLastLocationUpdate=" + this.mLastLocationUpdate);
            }
        }
        for (PendingIntent intent : exitIntents) {
            sendIntentExit(intent);
        }
        for (PendingIntent intent2 : enterIntents) {
            sendIntentEnter(intent2);
        }
    }

    private void sendIntentEnter(PendingIntent pendingIntent) {
        if (D) {
            Slog.d(TAG, "sendIntentEnter: pendingIntent=" + pendingIntent);
        }
        Intent intent = new Intent();
        intent.putExtra("entering", true);
        sendIntent(pendingIntent, intent);
    }

    private void sendIntentExit(PendingIntent pendingIntent) {
        if (D) {
            Slog.d(TAG, "sendIntentExit: pendingIntent=" + pendingIntent);
        }
        Intent intent = new Intent();
        intent.putExtra("entering", false);
        sendIntent(pendingIntent, intent);
    }

    private void sendIntent(PendingIntent pendingIntent, Intent intent) {
        this.mWakeLock.acquire();
        try {
            pendingIntent.send(this.mContext, 0, intent, this, null, "android.permission.ACCESS_FINE_LOCATION", PendingIntentUtils.createDontSendToRestrictedAppsBundle(null));
        } catch (PendingIntent.CanceledException e) {
            removeFence(null, pendingIntent);
            this.mWakeLock.release();
        }
    }

    @Override // android.location.LocationListener
    public void onLocationChanged(Location location) {
        synchronized (this.mLock) {
            if (this.mReceivingLocationUpdates) {
                this.mLastLocationUpdate = location;
            }
            if (this.mPendingUpdate) {
                this.mHandler.removeMessages(1);
            } else {
                this.mPendingUpdate = true;
            }
        }
        updateFences();
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

    @Override // android.app.PendingIntent.OnFinished
    public void onSendFinished(PendingIntent pendingIntent, Intent intent, int resultCode, String resultData, Bundle resultExtras) {
        this.mWakeLock.release();
    }

    public void dump(PrintWriter pw) {
        pw.println("  Geofences:");
        for (GeofenceState state : this.mFences) {
            pw.append("    ");
            pw.append((CharSequence) state.mPackageName);
            pw.append(" ");
            pw.append((CharSequence) state.mFence.toString());
            pw.append("\n");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class GeofenceHandler extends Handler {
        public GeofenceHandler() {
            super(true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
                GeofenceManager.this.updateFences();
            }
        }
    }
}
