package com.android.server.locksettings;

import android.app.AlarmManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.IStrongAuthTracker;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseIntArray;
import com.android.internal.widget.LockPatternUtils;

/* loaded from: classes.dex */
public class LockSettingsStrongAuth {
    private static final int MSG_REGISTER_TRACKER = 2;
    private static final int MSG_REMOVE_USER = 4;
    private static final int MSG_REQUIRE_STRONG_AUTH = 1;
    private static final int MSG_SCHEDULE_STRONG_AUTH_TIMEOUT = 5;
    private static final int MSG_UNREGISTER_TRACKER = 3;
    private static final String STRONG_AUTH_TIMEOUT_ALARM_TAG = "LockSettingsStrongAuth.timeoutForUser";
    private static final String TAG = "LockSettings";
    private AlarmManager mAlarmManager;
    private final Context mContext;
    private final int mDefaultStrongAuthFlags;
    private final RemoteCallbackList<IStrongAuthTracker> mTrackers = new RemoteCallbackList<>();
    private final SparseIntArray mStrongAuthForUser = new SparseIntArray();
    private final ArrayMap<Integer, StrongAuthTimeoutAlarmListener> mStrongAuthTimeoutAlarmListenerForUser = new ArrayMap<>();
    private final Handler mHandler = new Handler() { // from class: com.android.server.locksettings.LockSettingsStrongAuth.1
        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i == 1) {
                LockSettingsStrongAuth.this.handleRequireStrongAuth(msg.arg1, msg.arg2);
            } else if (i == 2) {
                LockSettingsStrongAuth.this.handleAddStrongAuthTracker((IStrongAuthTracker) msg.obj);
            } else if (i == 3) {
                LockSettingsStrongAuth.this.handleRemoveStrongAuthTracker((IStrongAuthTracker) msg.obj);
            } else if (i == 4) {
                LockSettingsStrongAuth.this.handleRemoveUser(msg.arg1);
            } else if (i == 5) {
                LockSettingsStrongAuth.this.handleScheduleStrongAuthTimeout(msg.arg1);
            }
        }
    };

    public LockSettingsStrongAuth(Context context) {
        this.mContext = context;
        this.mDefaultStrongAuthFlags = LockPatternUtils.StrongAuthTracker.getDefaultFlags(context);
        this.mAlarmManager = (AlarmManager) context.getSystemService(AlarmManager.class);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleAddStrongAuthTracker(IStrongAuthTracker tracker) {
        this.mTrackers.register(tracker);
        for (int i = 0; i < this.mStrongAuthForUser.size(); i++) {
            int key = this.mStrongAuthForUser.keyAt(i);
            int value = this.mStrongAuthForUser.valueAt(i);
            try {
                tracker.onStrongAuthRequiredChanged(value, key);
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception while adding StrongAuthTracker.", e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleRemoveStrongAuthTracker(IStrongAuthTracker tracker) {
        this.mTrackers.unregister(tracker);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleRequireStrongAuth(int strongAuthReason, int userId) {
        if (userId == -1) {
            for (int i = 0; i < this.mStrongAuthForUser.size(); i++) {
                int key = this.mStrongAuthForUser.keyAt(i);
                handleRequireStrongAuthOneUser(strongAuthReason, key);
            }
            return;
        }
        handleRequireStrongAuthOneUser(strongAuthReason, userId);
    }

    private void handleRequireStrongAuthOneUser(int strongAuthReason, int userId) {
        int newValue;
        int oldValue = this.mStrongAuthForUser.get(userId, this.mDefaultStrongAuthFlags);
        if (strongAuthReason == 0) {
            newValue = 0;
        } else {
            newValue = oldValue | strongAuthReason;
        }
        if (oldValue != newValue) {
            this.mStrongAuthForUser.put(userId, newValue);
            notifyStrongAuthTrackers(newValue, userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleRemoveUser(int userId) {
        int index = this.mStrongAuthForUser.indexOfKey(userId);
        if (index >= 0) {
            this.mStrongAuthForUser.removeAt(index);
            notifyStrongAuthTrackers(this.mDefaultStrongAuthFlags, userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleScheduleStrongAuthTimeout(int userId) {
        DevicePolicyManager dpm = (DevicePolicyManager) this.mContext.getSystemService("device_policy");
        long when = SystemClock.elapsedRealtime() + dpm.getRequiredStrongAuthTimeout(null, userId);
        StrongAuthTimeoutAlarmListener alarm = this.mStrongAuthTimeoutAlarmListenerForUser.get(Integer.valueOf(userId));
        if (alarm != null) {
            this.mAlarmManager.cancel(alarm);
        } else {
            alarm = new StrongAuthTimeoutAlarmListener(userId);
            this.mStrongAuthTimeoutAlarmListenerForUser.put(Integer.valueOf(userId), alarm);
        }
        this.mAlarmManager.set(3, when, STRONG_AUTH_TIMEOUT_ALARM_TAG, alarm, this.mHandler);
    }

    private void notifyStrongAuthTrackers(int strongAuthReason, int userId) {
        int i = this.mTrackers.beginBroadcast();
        while (i > 0) {
            i--;
            try {
                try {
                    this.mTrackers.getBroadcastItem(i).onStrongAuthRequiredChanged(strongAuthReason, userId);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Exception while notifying StrongAuthTracker.", e);
                }
            } finally {
                this.mTrackers.finishBroadcast();
            }
        }
    }

    public void registerStrongAuthTracker(IStrongAuthTracker tracker) {
        this.mHandler.obtainMessage(2, tracker).sendToTarget();
    }

    public void unregisterStrongAuthTracker(IStrongAuthTracker tracker) {
        this.mHandler.obtainMessage(3, tracker).sendToTarget();
    }

    public void removeUser(int userId) {
        this.mHandler.obtainMessage(4, userId, 0).sendToTarget();
    }

    public void requireStrongAuth(int strongAuthReason, int userId) {
        if (userId == -1 || userId >= 0) {
            this.mHandler.obtainMessage(1, strongAuthReason, userId).sendToTarget();
            return;
        }
        throw new IllegalArgumentException("userId must be an explicit user id or USER_ALL");
    }

    public void reportUnlock(int userId) {
        requireStrongAuth(0, userId);
    }

    public void reportSuccessfulStrongAuthUnlock(int userId) {
        this.mHandler.obtainMessage(5, userId, 0).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class StrongAuthTimeoutAlarmListener implements AlarmManager.OnAlarmListener {
        private final int mUserId;

        public StrongAuthTimeoutAlarmListener(int userId) {
            this.mUserId = userId;
        }

        @Override // android.app.AlarmManager.OnAlarmListener
        public void onAlarm() {
            LockSettingsStrongAuth.this.requireStrongAuth(16, this.mUserId);
        }
    }
}
