package com.android.server.location;

import android.app.PendingIntent;
import android.location.Geofence;
import android.location.Location;

/* loaded from: classes.dex */
public class GeofenceState {
    public static final int FLAG_ENTER = 1;
    public static final int FLAG_EXIT = 2;
    private static final int STATE_INSIDE = 1;
    private static final int STATE_OUTSIDE = 2;
    private static final int STATE_UNKNOWN = 0;
    public final int mAllowedResolutionLevel;
    public final long mExpireAt;
    public final Geofence mFence;
    public final PendingIntent mIntent;
    public final String mPackageName;
    public final int mUid;
    int mState = 0;
    double mDistanceToCenter = Double.MAX_VALUE;
    private final Location mLocation = new Location("");

    public GeofenceState(Geofence fence, long expireAt, int allowedResolutionLevel, int uid, String packageName, PendingIntent intent) {
        this.mFence = fence;
        this.mExpireAt = expireAt;
        this.mAllowedResolutionLevel = allowedResolutionLevel;
        this.mUid = uid;
        this.mPackageName = packageName;
        this.mIntent = intent;
        this.mLocation.setLatitude(fence.getLatitude());
        this.mLocation.setLongitude(fence.getLongitude());
    }

    public int processLocation(Location location) {
        this.mDistanceToCenter = this.mLocation.distanceTo(location);
        int prevState = this.mState;
        boolean inside = this.mDistanceToCenter <= ((double) Math.max(this.mFence.getRadius(), location.getAccuracy()));
        if (inside) {
            this.mState = 1;
            if (prevState != 1) {
                return 1;
            }
        } else {
            this.mState = 2;
            if (prevState == 1) {
                return 2;
            }
        }
        return 0;
    }

    public double getDistanceToBoundary() {
        if (Double.compare(this.mDistanceToCenter, Double.MAX_VALUE) == 0) {
            return Double.MAX_VALUE;
        }
        return Math.abs(this.mFence.getRadius() - this.mDistanceToCenter);
    }

    public String toString() {
        String state;
        int i = this.mState;
        if (i == 1) {
            state = "IN";
        } else if (i == 2) {
            state = "OUT";
        } else {
            state = "?";
        }
        return String.format("%s d=%.0f %s", this.mFence.toString(), Double.valueOf(this.mDistanceToCenter), state);
    }
}
