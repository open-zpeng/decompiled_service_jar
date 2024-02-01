package com.xiaopeng.server.policy;

import android.app.ActivityThread;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.WindowManager;
import com.android.server.am.AssistDataRequester;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.SystemGesturesPointerEventListener;
import com.xiaopeng.app.xpActivityManager;
import com.xiaopeng.bi.BiDataManager;
import com.xiaopeng.server.input.xpInputManagerService;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.view.SharedDisplayManager;
import com.xiaopeng.view.xpWindowManager;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/* loaded from: classes2.dex */
public class xpSystemGesturesListener {
    private static final int DEF_MAX_MOVE_ANGLE_FORM_BOTTOM = 45;
    private static final int DEF_MAX_MOVE_ANGLE_FORM_TOP = 45;
    private static final int DEF_MID_MOVE_ANGLE_FORM_LEFT = 70;
    private static final int DEF_MID_MOVE_ANGLE_FORM_RIGHT = 70;
    private static final int DEF_MIN_MOVE_ANGLE_FORM_LEFT = 65;
    private static final int DEF_MIN_MOVE_ANGLE_FORM_RIGHT = 65;
    private static final int DEF_MOVE_TIME_MAX = 50;
    private static final float DEF_MULTI_POINTER_DISTANCE_MAX = 12.0f;
    private static final float DEF_MULTI_POINTER_DISTANCE_MIN = 1.0f;
    private static final float DEF_MULTI_POINTER_MEAN_SPEED_MAX = 10.0f;
    private static final float DEF_MULTI_POINTER_MEAN_SPEED_MIN = 0.0f;
    private static final float DEF_MULTI_POINTER_SWIPE_SPEED_MAX = 1000.0f;
    private static final float DEF_MULTI_POINTER_SWIPE_SPEED_MIN = 0.3f;
    private static final int DEF_RELAX_MOVE_TIME = 30;
    private static final long DEF_TIME_LIMIT = 100;
    public static final String KEY_MAX_MOVE_ANGLE_FORM_BOTTOM = "key_gesture_max_move_angle_form_bottom";
    public static final String KEY_MAX_MOVE_ANGLE_FORM_TOP = "key_gesture_max_move_angle_form_top";
    public static final String KEY_MID_MOVE_ANGLE_FORM_LEFT = "key_gesture_mid_move_angle_form_left";
    public static final String KEY_MID_MOVE_ANGLE_FORM_RIGHT = "key_gesture_mid_move_angle_form_right";
    public static final String KEY_MIN_MOVE_ANGLE_FORM_LEFT = "key_gesture_min_move_angle_form_left";
    public static final String KEY_MIN_MOVE_ANGLE_FORM_RIGHT = "key_gesture_min_move_angle_form_right";
    public static final String KEY_MOVE_TIME_MAX = "key_gesture_move_time_max";
    public static final String KEY_MULTI_POINTER_DISTANCE_MAX = "key_gesture_multi_pointer_distance_max";
    public static final String KEY_MULTI_POINTER_DISTANCE_MIN = "key_gesture_multi_pointer_distance_min";
    public static final String KEY_MULTI_POINTER_MEAN_SPEED_MAX = "key_gesture_multi_pointer_mean_speed_max";
    public static final String KEY_MULTI_POINTER_MEAN_SPEED_MIN = "key_gesture_multi_pointer_mean_speed_min";
    public static final String KEY_MULTI_POINTER_SWIPE_SPEED_MAX = "key_gesture_multi_pointer_swipe_speed_max";
    public static final String KEY_MULTI_POINTER_SWIPE_SPEED_MIN = "key_gesture_multi_pointer_swipe_speed_min";
    public static final String KEY_MULTI_POINTER_TIME_MAX = "key_gesture_multi_pointer_time_max";
    public static final String KEY_MULTI_POINTER_TIME_MIN = "key_gesture_multi_pointer_time_min";
    public static final String KEY_RELAX_MOVE_TIME = "key_gesture_relax_move_time";
    public static final String KEY_TIME_LIMIT = "key_gesture_time_limit";
    private static final int MAX_MOVE_ANGLE_FORM_LEFT = 90;
    private static final int MAX_MOVE_ANGLE_FORM_RIGHT = 90;
    private static final int MAX_TRACKED_POINTERS = 32;
    static final int MSG_MOTION_EVENT = 1;
    public static final int MULTI_POINTER = 3;
    public static final int MULTI_POINTER_MAX = 3;
    public static final int MULTI_POINTER_MIN = 2;
    public static final int MULTI_SWIPE_FROM_BOTTOM = 2;
    public static final int MULTI_SWIPE_FROM_LEFT = 4;
    public static final int MULTI_SWIPE_FROM_NONE = 0;
    public static final int MULTI_SWIPE_FROM_RIGHT = 3;
    public static final int MULTI_SWIPE_FROM_TOP = 1;
    private static final long SWIPE_TIMEOUT_MS = 500;
    private static final int UNTRACKED_POINTER = -1;
    private static Handler xpSysGesHandler;
    public int screenHeight;
    public int screenWidth;
    private static final String TAG = "xpSystemGesturesListener";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);
    private static final Context mContext = ActivityThread.currentActivityThread().getSystemUiContext();
    private static HandlerThread xpSysHandlerTh = null;
    private static int DEF_MULTI_POINTER_TIME_MIN = 0;
    private static int DEF_MULTI_POINTER_TIME_MAX = 50;
    private static boolean isSharedDisplayEnabled = FeatureOption.FO_SHARED_DISPLAY_ENABLED;
    private static final int DEF_ACTIVITY_LIMIT = isSharedDisplayEnabled ? 1 : 0;
    public static final String KEY_ACTIVITY_LIMIT = "key_gesture_activity_limit";
    private static int ACTIVITY_LIMIT = Settings.System.getInt(mContext.getContentResolver(), KEY_ACTIVITY_LIMIT, DEF_ACTIVITY_LIMIT);
    private static int screenNum = FeatureOption.FO_SCREEN_NUM;
    private static List<xpSystemGesturesLimit> mxpSystemGesturesLimitList = getXpSystemGesturesLimitList();
    private static List<xpSystemGesturesMove> mxpSystemGesturesMoveList = getXpSystemGesturesMoveList();
    private static List<xpSystemGesturesDown> mxpSystemGesturesDownList = getXpSystemGesturesDownList();
    private final Handler mHandler = new Handler() { // from class: com.xiaopeng.server.policy.xpSystemGesturesListener.1
    };
    private VelocityTracker mVelocityTracker = VelocityTracker.obtain();
    public int mPointCount = 0;
    private int finalDirection = 0;
    public int dointercept = -1;
    public long startTime = 0;
    public long endTime = 0;
    public int mSwipeAngleYFlag = 0;
    public int mSwipeAngleValidFlag = 0;
    private xpSystemGesturesLimit nodirLimit = mxpSystemGesturesLimitList.get(0);
    private GesturesValueObserver mGesturesValueObserver = new GesturesValueObserver(this.mHandler);

    /* loaded from: classes2.dex */
    public static final class Pointer {
        public float x;
        public float y;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static class GesturesValueObserver extends ContentObserver {
        public GesturesValueObserver(Handler handler) {
            super(handler);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            onGesturesValueChanged();
        }

        public void startObserving() {
            ContentResolver resolver = xpSystemGesturesListener.mContext.getContentResolver();
            resolver.unregisterContentObserver(this);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_MAX_MOVE_ANGLE_FORM_TOP), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_MAX_MOVE_ANGLE_FORM_BOTTOM), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_MIN_MOVE_ANGLE_FORM_LEFT), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_MID_MOVE_ANGLE_FORM_LEFT), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_MIN_MOVE_ANGLE_FORM_RIGHT), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_MID_MOVE_ANGLE_FORM_RIGHT), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_MULTI_POINTER_DISTANCE_MIN), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_MULTI_POINTER_DISTANCE_MAX), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_MULTI_POINTER_TIME_MIN), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_MULTI_POINTER_TIME_MAX), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_MULTI_POINTER_SWIPE_SPEED_MIN), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_MULTI_POINTER_SWIPE_SPEED_MAX), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_MULTI_POINTER_MEAN_SPEED_MIN), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_MULTI_POINTER_MEAN_SPEED_MAX), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_TIME_LIMIT), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_ACTIVITY_LIMIT), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_MOVE_TIME_MAX), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor(xpSystemGesturesListener.KEY_RELAX_MOVE_TIME), false, this, -1);
            onGesturesValueChanged();
        }

        public void stopObserving() {
            ContentResolver resolver = xpSystemGesturesListener.mContext.getContentResolver();
            resolver.unregisterContentObserver(this);
        }

        public static void onGesturesValueChanged() {
            int unused = xpSystemGesturesListener.ACTIVITY_LIMIT = Settings.System.getIntForUser(xpSystemGesturesListener.mContext.getContentResolver(), xpSystemGesturesListener.KEY_ACTIVITY_LIMIT, xpSystemGesturesListener.DEF_ACTIVITY_LIMIT, -2);
            SystemProperties.set("persist.sys.activitylimit", Integer.toString(xpSystemGesturesListener.ACTIVITY_LIMIT));
            List unused2 = xpSystemGesturesListener.mxpSystemGesturesLimitList = xpSystemGesturesListener.getXpSystemGesturesLimitList();
            if (xpSystemGesturesListener.DEBUG) {
                xpLogger.i(xpSystemGesturesListener.TAG, "check xpSGL.onGesturesValueChanged screenNum=" + xpSystemGesturesListener.screenNum + " ,ACTIVITY_LIMIT=" + xpSystemGesturesListener.ACTIVITY_LIMIT);
            }
        }
    }

    public static List<xpSystemGesturesLimit> getXpSystemGesturesLimitList() {
        List<xpSystemGesturesLimit> xpSystemGesturesLimitList;
        synchronized (xpSystemGesturesLimit.class) {
            xpSystemGesturesLimitList = xpSystemGesturesLimitInfoLoad();
        }
        return xpSystemGesturesLimitList;
    }

    public static List<xpSystemGesturesDown> getXpSystemGesturesDownList() {
        List<xpSystemGesturesDown> xpSystemGesturesDownList;
        synchronized (xpSystemGesturesDown.class) {
            xpSystemGesturesDownList = initXpSystemGesturesDownList();
        }
        return xpSystemGesturesDownList;
    }

    public static List<xpSystemGesturesMove> getXpSystemGesturesMoveList() {
        List<xpSystemGesturesMove> xpSystemGesturesMoveList;
        synchronized (xpSystemGesturesMove.class) {
            xpSystemGesturesMoveList = initXpSystemGesturesMoveList();
        }
        return xpSystemGesturesMoveList;
    }

    private static List<xpSystemGesturesDown> initXpSystemGesturesDownList() {
        List<xpSystemGesturesDown> initDownList = new ArrayList<>();
        for (int i = 0; i < screenNum; i++) {
            xpSystemGesturesDown initDown = new xpSystemGesturesDown();
            initDown.screenId = i;
            initDown.downX = new float[32];
            initDown.downY = new float[32];
            initDown.downTime = new long[32];
            initDown.pointerIndex = new int[32];
            initDown.downPointerId = new int[32];
            initDown.downScreenId = new int[32];
            initDown.mScreenPointCount = 0;
            initDown.multiPointerValid = false;
            initDown.isTouchDistanceValid = false;
            initDown.isTouchTimeValid = false;
            initDown.mMultiPointer = false;
            initDownList.add(initDown);
        }
        return initDownList;
    }

    private static List<xpSystemGesturesMove> initXpSystemGesturesMoveList() {
        List<xpSystemGesturesMove> initMoveList = new ArrayList<>();
        for (int i = 0; i < screenNum; i++) {
            xpSystemGesturesMove initMove = new xpSystemGesturesMove();
            initMove.screenId = i;
            initMove.direction = 0;
            initMove.mWaitToken = false;
            initMove.isTotalTimeValid = false;
            initMove.isSwipeSpeedValid = false;
            initMove.isMeanSpeedValid = false;
            initMove.isSwipeDistanceValid = false;
            initMove.mSwipeFireable = false;
            initMove.isSwipeAreaValid = false;
            initMove.isSwipeAngleValid = false;
            initMove.isMultiPointerEvent = false;
            initMove.mMultiBroadcast = 0;
            initMoveList.add(initMove);
        }
        return initMoveList;
    }

    private static List<xpSystemGesturesLimit> xpSystemGesturesLimitInfoLoad() {
        List<xpSystemGesturesLimit> limitInfoList = new ArrayList<>();
        xpSystemGesturesLimit noneLimitInfo = new xpSystemGesturesLimit();
        xpSystemGesturesLimit topLimitInfo = new xpSystemGesturesLimit();
        xpSystemGesturesLimit bottomLimitInfo = new xpSystemGesturesLimit();
        xpSystemGesturesLimit rightLimitInfo = new xpSystemGesturesLimit();
        xpSystemGesturesLimit leftLimitInfo = new xpSystemGesturesLimit();
        noneLimitInfo.swipeDirection = 0;
        noneLimitInfo.multiPointerMin = 2;
        noneLimitInfo.multiPointerMax = 3;
        noneLimitInfo.multiPointerDistanceMin = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_DISTANCE_MIN, 1.0f);
        noneLimitInfo.multiPointerDistanceMax = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_DISTANCE_MAX, DEF_MULTI_POINTER_DISTANCE_MAX);
        noneLimitInfo.multiPointerSwipeSpeedMin = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_SWIPE_SPEED_MIN, DEF_MULTI_POINTER_SWIPE_SPEED_MIN);
        noneLimitInfo.multiPointerSwipeSpeedMax = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_SWIPE_SPEED_MAX, DEF_MULTI_POINTER_SWIPE_SPEED_MAX);
        noneLimitInfo.multiPointerMeanSpeedMin = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_MEAN_SPEED_MIN, DEF_MULTI_POINTER_MEAN_SPEED_MIN);
        noneLimitInfo.multiPointerMeanSpeedMax = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_MEAN_SPEED_MAX, DEF_MULTI_POINTER_MEAN_SPEED_MAX);
        noneLimitInfo.multiMoveTimeMin = Settings.System.getInt(mContext.getContentResolver(), KEY_MULTI_POINTER_TIME_MIN, DEF_MULTI_POINTER_TIME_MIN);
        noneLimitInfo.multiMoveTimeMax = Settings.System.getInt(mContext.getContentResolver(), KEY_MULTI_POINTER_TIME_MAX, DEF_MULTI_POINTER_TIME_MAX);
        noneLimitInfo.mSwipeStartThreshold = SystemProperties.getInt("persist.data.gestures.swipethreshold", 100);
        noneLimitInfo.mSwipeDistanceThreshold = topLimitInfo.mSwipeStartThreshold;
        topLimitInfo.swipeDirection = 1;
        topLimitInfo.multiPointerMin = 2;
        topLimitInfo.multiPointerMax = 3;
        topLimitInfo.multiMoveAngleMin = 0;
        topLimitInfo.multiMoveAngleMax = Settings.System.getInt(mContext.getContentResolver(), KEY_MAX_MOVE_ANGLE_FORM_TOP, 45);
        topLimitInfo.multiPointerDistanceMin = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_DISTANCE_MIN, 1.0f);
        topLimitInfo.multiPointerDistanceMax = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_DISTANCE_MAX, DEF_MULTI_POINTER_DISTANCE_MAX);
        topLimitInfo.multiPointerSwipeSpeedMin = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_SWIPE_SPEED_MIN, DEF_MULTI_POINTER_SWIPE_SPEED_MIN);
        topLimitInfo.multiPointerSwipeSpeedMax = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_SWIPE_SPEED_MAX, DEF_MULTI_POINTER_SWIPE_SPEED_MAX);
        topLimitInfo.multiPointerMeanSpeedMin = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_MEAN_SPEED_MIN, DEF_MULTI_POINTER_MEAN_SPEED_MIN);
        topLimitInfo.multiPointerMeanSpeedMax = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_MEAN_SPEED_MAX, DEF_MULTI_POINTER_MEAN_SPEED_MAX);
        topLimitInfo.multiMoveTimeMin = Settings.System.getInt(mContext.getContentResolver(), KEY_MULTI_POINTER_TIME_MIN, DEF_MULTI_POINTER_TIME_MIN);
        topLimitInfo.multiMoveTimeMax = Settings.System.getInt(mContext.getContentResolver(), KEY_MULTI_POINTER_TIME_MAX, DEF_MULTI_POINTER_TIME_MAX);
        topLimitInfo.mSwipeStartThreshold = SystemProperties.getInt("persist.data.gestures.swipethreshold", 100);
        topLimitInfo.mSwipeDistanceThreshold = topLimitInfo.mSwipeStartThreshold;
        bottomLimitInfo.swipeDirection = 2;
        bottomLimitInfo.multiPointerMin = 2;
        bottomLimitInfo.multiPointerMax = 3;
        bottomLimitInfo.multiMoveAngleMin = 0;
        bottomLimitInfo.multiMoveAngleMax = Settings.System.getInt(mContext.getContentResolver(), KEY_MAX_MOVE_ANGLE_FORM_BOTTOM, 45);
        bottomLimitInfo.multiPointerDistanceMin = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_DISTANCE_MIN, 1.0f);
        bottomLimitInfo.multiPointerDistanceMax = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_DISTANCE_MAX, DEF_MULTI_POINTER_DISTANCE_MAX);
        bottomLimitInfo.multiPointerSwipeSpeedMin = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_SWIPE_SPEED_MIN, DEF_MULTI_POINTER_SWIPE_SPEED_MIN);
        bottomLimitInfo.multiPointerSwipeSpeedMax = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_SWIPE_SPEED_MAX, DEF_MULTI_POINTER_SWIPE_SPEED_MAX);
        bottomLimitInfo.multiPointerMeanSpeedMin = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_MEAN_SPEED_MIN, DEF_MULTI_POINTER_MEAN_SPEED_MIN);
        bottomLimitInfo.multiPointerMeanSpeedMax = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_MEAN_SPEED_MAX, DEF_MULTI_POINTER_MEAN_SPEED_MAX);
        bottomLimitInfo.multiMoveTimeMin = Settings.System.getInt(mContext.getContentResolver(), KEY_MULTI_POINTER_TIME_MIN, DEF_MULTI_POINTER_TIME_MIN);
        bottomLimitInfo.multiMoveTimeMax = Settings.System.getInt(mContext.getContentResolver(), KEY_MULTI_POINTER_TIME_MAX, DEF_MULTI_POINTER_TIME_MAX);
        bottomLimitInfo.mSwipeStartThreshold = SystemProperties.getInt("persist.data.gestures.swipethreshold", 100);
        bottomLimitInfo.mSwipeDistanceThreshold = bottomLimitInfo.mSwipeStartThreshold;
        rightLimitInfo.swipeDirection = 3;
        rightLimitInfo.multiPointerMin = 2;
        rightLimitInfo.multiPointerMax = 3;
        rightLimitInfo.multiMoveAngleMin = Settings.System.getInt(mContext.getContentResolver(), KEY_MIN_MOVE_ANGLE_FORM_RIGHT, 65);
        rightLimitInfo.multiMoveAngleMid = Settings.System.getInt(mContext.getContentResolver(), KEY_MID_MOVE_ANGLE_FORM_RIGHT, 70);
        rightLimitInfo.multiMoveAngleMax = 90;
        rightLimitInfo.multiPointerDistanceMin = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_DISTANCE_MIN, 1.0f);
        rightLimitInfo.multiPointerDistanceMax = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_DISTANCE_MAX, DEF_MULTI_POINTER_DISTANCE_MAX);
        rightLimitInfo.multiPointerSwipeSpeedMin = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_SWIPE_SPEED_MIN, DEF_MULTI_POINTER_SWIPE_SPEED_MIN);
        rightLimitInfo.multiPointerSwipeSpeedMax = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_SWIPE_SPEED_MAX, DEF_MULTI_POINTER_SWIPE_SPEED_MAX);
        rightLimitInfo.multiPointerMeanSpeedMin = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_MEAN_SPEED_MIN, DEF_MULTI_POINTER_MEAN_SPEED_MIN);
        rightLimitInfo.multiPointerMeanSpeedMax = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_MEAN_SPEED_MAX, DEF_MULTI_POINTER_MEAN_SPEED_MAX);
        rightLimitInfo.multiMoveTimeMin = Settings.System.getInt(mContext.getContentResolver(), KEY_MULTI_POINTER_TIME_MIN, DEF_MULTI_POINTER_TIME_MIN);
        rightLimitInfo.multiMoveTimeMax = Settings.System.getInt(mContext.getContentResolver(), KEY_MULTI_POINTER_TIME_MAX, DEF_MULTI_POINTER_TIME_MAX);
        rightLimitInfo.mSwipeStartThreshold = SystemProperties.getInt("persist.data.gestures.swipethreshold", 100);
        rightLimitInfo.mSwipeDistanceThreshold = rightLimitInfo.mSwipeStartThreshold;
        leftLimitInfo.swipeDirection = 4;
        leftLimitInfo.multiPointerMin = 2;
        leftLimitInfo.multiPointerMax = 3;
        leftLimitInfo.multiMoveAngleMin = Settings.System.getInt(mContext.getContentResolver(), KEY_MIN_MOVE_ANGLE_FORM_LEFT, 65);
        leftLimitInfo.multiMoveAngleMid = Settings.System.getInt(mContext.getContentResolver(), KEY_MID_MOVE_ANGLE_FORM_LEFT, 70);
        leftLimitInfo.multiMoveAngleMax = 90;
        leftLimitInfo.multiPointerDistanceMin = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_DISTANCE_MIN, 1.0f);
        leftLimitInfo.multiPointerDistanceMax = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_DISTANCE_MAX, DEF_MULTI_POINTER_DISTANCE_MAX);
        leftLimitInfo.multiPointerSwipeSpeedMin = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_SWIPE_SPEED_MIN, DEF_MULTI_POINTER_SWIPE_SPEED_MIN);
        leftLimitInfo.multiPointerSwipeSpeedMax = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_SWIPE_SPEED_MAX, DEF_MULTI_POINTER_SWIPE_SPEED_MAX);
        leftLimitInfo.multiPointerMeanSpeedMin = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_MEAN_SPEED_MIN, DEF_MULTI_POINTER_MEAN_SPEED_MIN);
        leftLimitInfo.multiPointerMeanSpeedMax = Settings.System.getFloat(mContext.getContentResolver(), KEY_MULTI_POINTER_MEAN_SPEED_MAX, DEF_MULTI_POINTER_MEAN_SPEED_MAX);
        leftLimitInfo.multiMoveTimeMin = Settings.System.getInt(mContext.getContentResolver(), KEY_MULTI_POINTER_TIME_MIN, DEF_MULTI_POINTER_TIME_MIN);
        leftLimitInfo.multiMoveTimeMax = Settings.System.getInt(mContext.getContentResolver(), KEY_MULTI_POINTER_TIME_MAX, DEF_MULTI_POINTER_TIME_MAX);
        leftLimitInfo.mSwipeStartThreshold = SystemProperties.getInt("persist.data.gestures.swipethreshold", 100);
        leftLimitInfo.mSwipeDistanceThreshold = leftLimitInfo.mSwipeStartThreshold;
        limitInfoList.add(noneLimitInfo);
        limitInfoList.add(topLimitInfo);
        limitInfoList.add(bottomLimitInfo);
        limitInfoList.add(rightLimitInfo);
        limitInfoList.add(leftLimitInfo);
        return limitInfoList;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static class xpSystemGesturesLimit {
        public int mSwipeDistanceThreshold;
        public int mSwipeStartThreshold;
        public int multiMoveAngleMax;
        public int multiMoveAngleMid;
        public int multiMoveAngleMin;
        public int multiMoveTimeMax;
        public int multiMoveTimeMin;
        public float multiPointerDistanceMax;
        public float multiPointerDistanceMin;
        public int multiPointerMax;
        public float multiPointerMeanSpeedMax;
        public float multiPointerMeanSpeedMin;
        public int multiPointerMin;
        public float multiPointerSwipeSpeedMax;
        public float multiPointerSwipeSpeedMin;
        public int swipeDirection;

        private xpSystemGesturesLimit() {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static class xpSystemGesturesDown {
        public int[] downPointerId;
        public int[] downScreenId;
        public long[] downTime;
        public float[] downX;
        public float[] downY;
        public boolean isTouchDistanceValid;
        public boolean isTouchTimeValid;
        public boolean mMultiPointer;
        public int mScreenPointCount;
        public boolean multiPointerValid;
        public int[] pointerIndex;
        public int screenId;

        private xpSystemGesturesDown() {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static class xpSystemGesturesMove {
        public int direction;
        public boolean isMeanSpeedValid;
        public boolean isMultiPointerEvent;
        public boolean isSwipeAngleValid;
        public boolean isSwipeAreaValid;
        public boolean isSwipeDistanceValid;
        public boolean isSwipeSpeedValid;
        public boolean isTotalTimeValid;
        public int mMultiBroadcast;
        public boolean mSwipeFireable;
        public boolean mWaitToken;
        public int screenId;

        private xpSystemGesturesMove() {
        }
    }

    public void init() {
        GesturesValueObserver gesturesValueObserver = this.mGesturesValueObserver;
        if (gesturesValueObserver != null) {
            gesturesValueObserver.startObserving();
        }
        if (xpSysHandlerTh == null) {
            xpSysHandlerTh = new HandlerThread("xpSystemGesture");
            xpSysHandlerTh.start();
        }
    }

    public void onPointerEvent(Context context, MotionEvent event) {
        MotionEvent priEvent;
        MotionEvent secEvent;
        printMotionEvent(event);
        if (DEBUG) {
            xpLogger.i(TAG, "xpSGL.onPointerEvent getScreen= " + SharedDisplayManager.findScreenId(event.getActionIndex(), event) + " event=" + event);
        }
        int actionMasked = event.getActionMasked();
        if (actionMasked == 0 || actionMasked == 1 || actionMasked == 3 || actionMasked != 6) {
        }
        boolean isPriSwipeValid = SystemProperties.getInt("persist.sys.donavipri", -100) != 0;
        boolean isSecSwipeValid = SystemProperties.getInt("persist.sys.donavisec", -100) != 1;
        boolean isBothSwipeValid = SystemProperties.getInt("persist.sys.donavipri", -100) == -100 && SystemProperties.getInt("persist.sys.donavisec", -100) == -100;
        if (DEBUG) {
            xpLogger.i(TAG, "xpSGL.onPointerEvent dispatch to lister isPriSwipeValid=" + isPriSwipeValid + " isSecSwipeValid=" + isSecSwipeValid + " isBothSwipeValid=" + isBothSwipeValid + " SystemProperties.getInt(persist.sys.donavipri,-100)=" + SystemProperties.getInt("persist.sys.donavipri", -100) + " SystemProperties.getInt(persist.sys.donavisec,-100)=" + SystemProperties.getInt("persist.sys.donavisec", -100) + " event=" + event);
        }
        if (isBothSwipeValid) {
            dispatchMotionEvent(context, event, -1);
            return;
        }
        MotionEvent priEvent2 = MotionEvent.getScreenMotionEvent(event, 0);
        MotionEvent secEvent2 = MotionEvent.getScreenMotionEvent(event, 1);
        if (DEBUG) {
            xpLogger.i(TAG, "xpSGL.onPointerEvent priEvent=" + priEvent2);
        }
        if (DEBUG) {
            xpLogger.i(TAG, "xpSGL.onPointerEvent secEvent=" + secEvent2);
        }
        if (isPriSwipeValid && priEvent2 != null) {
            dispatchMotionEvent(context, priEvent2, 0);
        } else if (!isPriSwipeValid && priEvent2 != null && (priEvent = MotionEvent.obtain(priEvent2.getDownTime(), priEvent2.getEventTime(), 3, priEvent2.getX(), priEvent2.getY(), 0)) != null) {
            dispatchMotionEvent(context, priEvent, 0);
        }
        if (isSecSwipeValid && secEvent2 != null) {
            dispatchMotionEvent(context, secEvent2, 1);
        } else if (!isSecSwipeValid && secEvent2 != null && (secEvent = MotionEvent.obtain(secEvent2.getDownTime(), secEvent2.getEventTime(), 3, secEvent2.getX(), secEvent2.getY(), 0)) != null) {
            dispatchMotionEvent(context, secEvent, 1);
        }
    }

    public void captureDown(MotionEvent event, int pointerIndex, int screenId, float[] downX, float[] downY, long[] downTime, int[] downScreenId, int[] downPointerId) {
        int actionMasked = event.getActionMasked();
        if (actionMasked == 0) {
            for (int i = 0; i < mxpSystemGesturesMoveList.size(); i++) {
                mxpSystemGesturesMoveList.get(i).mSwipeFireable = true;
            }
        } else if (actionMasked == 5) {
            mxpSystemGesturesMoveList.get(screenId).mSwipeFireable = true;
        }
        dispatchDownEvent(event, pointerIndex, downX, downY, downTime, downScreenId, downPointerId);
        if (mxpSystemGesturesDownList.get(screenId).downX[0] > DEF_MULTI_POINTER_MEAN_SPEED_MIN) {
            detectDownEvent(screenId, event);
        }
    }

    public void dispatchDownEvent(MotionEvent event, int pointerIndex, float[] downX, float[] downY, long[] downTime, int[] downPointerScreenId, int[] downPointerId) {
        mxpSystemGesturesDownList = initXpSystemGesturesDownList();
        int[] searchIndex = new int[screenNum];
        for (int i = 0; i <= pointerIndex; i++) {
            mxpSystemGesturesDownList.get(downPointerScreenId[i]).downX[searchIndex[downPointerScreenId[i]]] = downX[i];
            mxpSystemGesturesDownList.get(downPointerScreenId[i]).downY[searchIndex[downPointerScreenId[i]]] = downY[i];
            mxpSystemGesturesDownList.get(downPointerScreenId[i]).downTime[searchIndex[downPointerScreenId[i]]] = downTime[i];
            mxpSystemGesturesDownList.get(downPointerScreenId[i]).pointerIndex[searchIndex[downPointerScreenId[i]]] = i;
            mxpSystemGesturesDownList.get(downPointerScreenId[i]).downPointerId[searchIndex[downPointerScreenId[i]]] = downPointerId[i];
            mxpSystemGesturesDownList.get(downPointerScreenId[i]).downScreenId[searchIndex[downPointerScreenId[i]]] = downPointerScreenId[i];
            int i2 = downPointerScreenId[i];
            searchIndex[i2] = searchIndex[i2] + 1;
        }
    }

    public void detectDownEvent(int screenId, MotionEvent event) {
        mxpSystemGesturesMoveList.get(screenId).mMultiBroadcast = (event.getPointerCount() < 2 || mxpSystemGesturesMoveList.get(screenId).mMultiBroadcast <= 0) ? 0 : mxpSystemGesturesMoveList.get(screenId).mMultiBroadcast;
        mxpSystemGesturesDownList.get(screenId).mScreenPointCount = getScreenPointCount(mxpSystemGesturesDownList.get(screenId).downX);
        mxpSystemGesturesDownList.get(screenId).multiPointerValid = mxpSystemGesturesDownList.get(screenId).mScreenPointCount >= this.nodirLimit.multiPointerMin;
        mxpSystemGesturesDownList.get(screenId).isTouchDistanceValid = isTouchDistanceValid(mxpSystemGesturesDownList.get(screenId).downX, mxpSystemGesturesDownList.get(screenId).downY, mxpSystemGesturesDownList.get(screenId).mScreenPointCount);
        mxpSystemGesturesDownList.get(screenId).isTouchTimeValid = isTouchTimeValid(mxpSystemGesturesDownList.get(screenId).downTime, mxpSystemGesturesDownList.get(screenId).mScreenPointCount);
        if (mxpSystemGesturesDownList.get(screenId).multiPointerValid && mxpSystemGesturesDownList.get(screenId).isTouchDistanceValid && mxpSystemGesturesDownList.get(screenId).isTouchTimeValid) {
            mxpSystemGesturesDownList.get(screenId).mMultiPointer = true;
            mxpSystemGesturesMoveList.get(screenId).mWaitToken = true;
            return;
        }
        mxpSystemGesturesDownList.get(screenId).mMultiPointer = false;
        mxpSystemGesturesMoveList.get(screenId).mWaitToken = false;
    }

    public void captureUpOrCancel(MotionEvent event, int screenId, SystemGesturesPointerEventListener.Callbacks callbacks, float[] downX, float[] downY, long[] downTime) {
        int actionMasked = event.getActionMasked();
        if (actionMasked == 1) {
            this.dointercept = -1;
            SystemProperties.set("persist.sys.dointercept", "-1");
            mxpSystemGesturesDownList = initXpSystemGesturesDownList();
            mxpSystemGesturesMoveList = initXpSystemGesturesMoveList();
        } else if (actionMasked == 3) {
            this.dointercept = -1;
            SystemProperties.set("persist.sys.dointercept", "-1");
            mxpSystemGesturesDownList = initXpSystemGesturesDownList();
            mxpSystemGesturesMoveList = initXpSystemGesturesMoveList();
        } else if (actionMasked == 6) {
            mxpSystemGesturesDownList.get(screenId).mScreenPointCount = mxpSystemGesturesDownList.get(screenId).mScreenPointCount > 0 ? mxpSystemGesturesDownList.get(screenId).mScreenPointCount - 1 : 0;
            mxpSystemGesturesMoveList.get(screenId).mMultiBroadcast = mxpSystemGesturesDownList.get(screenId).mScreenPointCount > 0 ? mxpSystemGesturesMoveList.get(screenId).mMultiBroadcast : 0;
            mxpSystemGesturesDownList.get(screenId).mMultiPointer = false;
            mxpSystemGesturesMoveList.get(screenId).mSwipeFireable = mxpSystemGesturesDownList.get(screenId).mScreenPointCount <= 0;
        }
    }

    public void onMultiPointerEvent(MotionEvent event, int screenId, SystemGesturesPointerEventListener.Callbacks callbacks, float[] downX, float[] downY, long[] downTime) {
        xpSystemGesturesDown down = mxpSystemGesturesDownList.get(screenId);
        if (down.downX[0] > DEF_MULTI_POINTER_MEAN_SPEED_MIN) {
            detectXpSystemGesturesEvent(event, screenId, callbacks, downX, downY, downTime, down);
        }
    }

    public static boolean in(long value, long min, long max) {
        return value <= max && value >= min;
    }

    public static boolean out(long value, long min, long max) {
        return value > max || value < min;
    }

    private boolean isTouchTimeValid(long[] downTime, int mScreenPointCount) {
        if (downTime[0] <= 0 || mScreenPointCount < this.nodirLimit.multiPointerMin) {
            return false;
        }
        for (int j = 1; j < this.nodirLimit.multiPointerMin; j++) {
            if (DEBUG) {
                xpLogger.i(TAG, "check xpSGL.isTouchTimeValid j=" + j + " downTime[j]-downTime[0]=" + (downTime[j] - downTime[0]) + " nodirLimit.multiMoveTimeMax=" + this.nodirLimit.multiMoveTimeMax);
            }
            if (downTime[j] - downTime[0] < 0 || downTime[j] - downTime[0] > this.nodirLimit.multiMoveTimeMax) {
                return false;
            }
        }
        return true;
    }

    private boolean isTouchDistanceValid(float[] downX, float[] downY, int mScreenPointCount) {
        double pixel = centimeterToPixel(this.screenHeight, this.nodirLimit.multiPointerDistanceMax);
        if (downX[0] <= DEF_MULTI_POINTER_MEAN_SPEED_MIN || mScreenPointCount < this.nodirLimit.multiPointerMin) {
            return false;
        }
        for (int j = 0; j < this.nodirLimit.multiPointerMin - 1; j++) {
            if (DEBUG) {
                xpLogger.i(TAG, "check xpSGL.isTouchDistanceValid start j=" + j + " getDistance=" + getDistance(getPointer(j, downX, downY), getPointer(j + 1, downX, downY)) + " pixel=" + pixel);
            }
            if (getDistance(getPointer(j, downX, downY), getPointer(j + 1, downX, downY)) >= pixel) {
                return false;
            }
        }
        return true;
    }

    private boolean isSwipeAngleValid(MotionEvent event, int count, Pointer[] p, Pointer[] dp, int[] angleArr, double[] xDistanceArr, double[] yDistanceArr, int[] dX, int[] dY, int[] dAngleR, int[] dAngleL, int[] pointerIndex, int direction) {
        if (count >= this.nodirLimit.multiPointerMin) {
            if (direction != 3) {
                if (direction != 4) {
                    return false;
                }
                xpSystemGesturesLimit leftLimit = mxpSystemGesturesLimitList.get(direction);
                if (event.getPointerCount() >= 2) {
                    this.mSwipeAngleYFlag = 0;
                    this.mSwipeAngleValidFlag = 0;
                    for (int i = 0; i < leftLimit.multiPointerMin; i++) {
                        this.mSwipeAngleYFlag += dY[i];
                        this.mSwipeAngleValidFlag += dAngleL[i];
                        if (DEBUG) {
                            xpLogger.i(TAG, " check the isSwipeAngleValid i=" + i + "dY[i]=" + dY[i] + " dAngleL[i]=" + dAngleL[i] + " mSwipeAngleYFlag=" + this.mSwipeAngleYFlag + " mSwipeAngleValidFlag=" + this.mSwipeAngleValidFlag);
                        }
                    }
                    if (DEBUG) {
                        xpLogger.i(TAG, " check the MULTI_SWIPE_FROM_LEFT isSwipeAngleValid mSwipeAngleYFlag=" + this.mSwipeAngleYFlag + " mSwipeAngleValidFlag=" + this.mSwipeAngleValidFlag);
                    }
                    int i2 = this.mSwipeAngleYFlag;
                    return i2 == 0 || i2 == leftLimit.multiPointerMin || this.mSwipeAngleValidFlag == leftLimit.multiPointerMin;
                }
                return false;
            }
            xpSystemGesturesLimit rightLimit = mxpSystemGesturesLimitList.get(direction);
            if (event.getPointerCount() >= 2) {
                this.mSwipeAngleYFlag = 0;
                this.mSwipeAngleValidFlag = 0;
                for (int i3 = 0; i3 < rightLimit.multiPointerMin; i3++) {
                    this.mSwipeAngleYFlag += dY[i3];
                    this.mSwipeAngleValidFlag += dAngleR[i3];
                    if (DEBUG) {
                        xpLogger.i(TAG, " check the isSwipeAngleValid i=" + i3 + "dY[i]=" + dY[i3] + " dAngleL[i]=" + dAngleL[i3] + " angleArr[i]=" + angleArr[i3] + " mSwipeAngleYFlag=" + this.mSwipeAngleYFlag + " mSwipeAngleValidFlag=" + this.mSwipeAngleValidFlag);
                    }
                }
                if (DEBUG) {
                    xpLogger.i(TAG, " check the MULTI_SWIPE_FROM_RIGHT isSwipeAngleValid mSwipeAngleYFlag=" + this.mSwipeAngleYFlag + " mSwipeAngleValidFlag=" + this.mSwipeAngleValidFlag);
                }
                int i4 = this.mSwipeAngleYFlag;
                return i4 == 0 || i4 == rightLimit.multiPointerMin || this.mSwipeAngleValidFlag == rightLimit.multiPointerMin;
            }
            return false;
        }
        return false;
    }

    private boolean isSwipeAreaValid(MotionEvent event, int count, Pointer[] dp, double[] xDistanceArr, double[] yDistanceArr, int[] pointerIndex, int[] downScreenId, int direction) {
        xpSystemGesturesLimit mLimit = mxpSystemGesturesLimitList.get(direction);
        int mSwipeStartThreshold = mLimit.mSwipeStartThreshold;
        int mSwipeDistanceThreshold = mLimit.mSwipeDistanceThreshold;
        if (event == null || event.getPointerCount() < count) {
            return false;
        }
        for (int i = 0; i < count && downScreenId[i] == SharedDisplayManager.findScreenId(pointerIndex[i], event) && dp[i] != null; i++) {
            if (dp[i].y <= mSwipeStartThreshold && yDistanceArr[i] > mSwipeDistanceThreshold) {
                return true;
            }
            if (dp[i].y >= this.screenHeight - mSwipeStartThreshold && yDistanceArr[i] < (-mSwipeDistanceThreshold)) {
                return true;
            }
            if (dp[i].x >= this.screenWidth - mSwipeStartThreshold && xDistanceArr[i] < (-mSwipeDistanceThreshold)) {
                return true;
            }
            if (dp[i].x <= mSwipeStartThreshold && xDistanceArr[i] > mSwipeDistanceThreshold) {
                return true;
            }
        }
        return false;
    }

    private boolean isSwipeDistanceValid(MotionEvent event, int count, Pointer[] p, Pointer[] dp, double[] distanceArr, int direction) {
        xpSystemGesturesLimit mLimit = mxpSystemGesturesLimitList.get(direction);
        double pixel = centimeterToPixel(this.screenHeight, mLimit.multiPointerDistanceMin);
        if (event != null && event.getPointerCount() >= mLimit.multiPointerMin) {
            for (int i = 0; i < count; i++) {
                try {
                    distanceArr[i] = getDistance(dp[i], p[i]);
                    if (DEBUG) {
                        xpLogger.i(TAG, "check xpSGL.isSwipeDistanceValid count=" + count + " i=" + i + " p[i].x=" + p[i].x + " p[i].y=" + p[i].y + " dp[i].x=" + dp[i].x + " dp[i].y=" + dp[i].y + " distanceArr[i]=" + distanceArr[i] + " pixel=" + pixel);
                    }
                    if (distanceArr[i] <= pixel) {
                        return false;
                    }
                } catch (Exception e) {
                }
            }
            return true;
        }
        return false;
    }

    private boolean isSwipeSpeedValid(MotionEvent event, int count, double[] swipespeedArr, int direction) {
        xpSystemGesturesLimit mLimit = mxpSystemGesturesLimitList.get(direction);
        if (event != null && event.getPointerCount() >= count) {
            double pixel1 = mLimit.multiPointerSwipeSpeedMin;
            double pixel2 = mLimit.multiPointerSwipeSpeedMax;
            for (int i = 0; i < count; i++) {
                try {
                    if (DEBUG) {
                        xpLogger.i(TAG, "check xpSGL.isSwipeSpeedValid count=" + count + " i=" + i + " swipespeedArr[i]=" + swipespeedArr[i] + " pixel1=" + pixel1 + " pixel2=" + pixel2);
                    }
                    if (swipespeedArr[i] <= pixel1 || swipespeedArr[i] >= pixel2) {
                        return false;
                    }
                } catch (Exception e) {
                }
            }
            return true;
        }
        return false;
    }

    private boolean isMeanSpeedValid(MotionEvent event, int count, double[] meanSpeedArr, int direction) {
        xpSystemGesturesLimit mLimit = mxpSystemGesturesLimitList.get(direction);
        if (event != null && event.getPointerCount() >= count) {
            double pixel1 = mLimit.multiPointerMeanSpeedMin;
            double pixel2 = mLimit.multiPointerMeanSpeedMax;
            for (int i = 0; i < count; i++) {
                try {
                    if (DEBUG) {
                        xpLogger.i(TAG, "check xpSGL.isMeanSpeedValid count=" + count + " i=" + i + " meanSpeedArr[i]=" + meanSpeedArr[i] + " pixel1=" + pixel1 + " pixel2=" + pixel2);
                    }
                    if (meanSpeedArr[i] <= pixel1 || meanSpeedArr[i] >= pixel2) {
                        return false;
                    }
                } catch (Exception e) {
                }
            }
            return true;
        }
        return false;
    }

    private boolean isTotalTimeValid(MotionEvent event, int count, long[] totalTime, long[] DownTime, int screenId, int direction) {
        xpSystemGesturesLimit mLimit = mxpSystemGesturesLimitList.get(direction);
        if (event != null && event.getPointerCount() >= mLimit.multiPointerMin && DownTime[0] > 0) {
            long limitTime = mLimit.multiMoveTimeMax + 50;
            if (DEBUG) {
                xpLogger.i(TAG, "check xpSGL.isTotalTimeValid screenId=" + screenId + " totalTime[0] =" + totalTime[0] + " limitTime=" + limitTime);
            }
            if (0 >= count || totalTime[0] > limitTime) {
                return false;
            }
            return true;
        }
        return false;
    }

    private int getSwipeDirection(MotionEvent event, int count, int screenId, int[] angleArr, double[] xDistanceArr, double[] yDistanceArr, int[] pointerIndex, int[] downScreenId) {
        boolean swipeFromLeft;
        boolean swipeFromTop;
        boolean swipeFromBottom;
        MotionEvent motionEvent = event;
        boolean swipeFromTop2 = true;
        boolean swipeFromBottom2 = true;
        boolean swipeFromRight = true;
        xpSystemGesturesLimit noneLimit = mxpSystemGesturesLimitList.get(0);
        xpSystemGesturesLimit topLimit = mxpSystemGesturesLimitList.get(1);
        xpSystemGesturesLimit bottomLimit = mxpSystemGesturesLimitList.get(2);
        xpSystemGesturesLimit rightLimit = mxpSystemGesturesLimitList.get(3);
        xpSystemGesturesLimit leftLimit = mxpSystemGesturesLimitList.get(4);
        if (motionEvent != null && event.getPointerCount() >= count) {
            int i = 0;
            swipeFromLeft = true;
            boolean swipeFromRight2 = true;
            boolean swipeFromBottom3 = true;
            boolean swipeFromRight3 = true;
            while (i < count) {
                xpSystemGesturesLimit noneLimit2 = noneLimit;
                try {
                    xpSystemGesturesLimit bottomLimit2 = bottomLimit;
                    try {
                        if (downScreenId[i] == SharedDisplayManager.findScreenId(pointerIndex[i], motionEvent)) {
                            if (DEBUG) {
                                xpLogger.i(TAG, "check xpSGL.getSwipeDirection screenId=" + screenId + " count=" + count + " i=" + i + " angleArr[i]=" + angleArr[i]);
                            }
                            swipeFromRight3 = (yDistanceArr[i] <= 0.0d || angleArr[i] > topLimit.multiMoveAngleMax) ? false : false;
                            try {
                                swipeFromBottom3 = (yDistanceArr[i] > 0.0d || angleArr[i] > topLimit.multiMoveAngleMax) ? false : false;
                                try {
                                    if (xDistanceArr[i] <= 0.0d) {
                                        swipeFromTop = swipeFromRight3;
                                        swipeFromBottom = swipeFromBottom3;
                                        try {
                                            if (out(angleArr[i], rightLimit.multiMoveAngleMin, rightLimit.multiMoveAngleMax)) {
                                            }
                                            if (xDistanceArr[i] > 0.0d || out(angleArr[i], leftLimit.multiMoveAngleMin, leftLimit.multiMoveAngleMax)) {
                                                swipeFromLeft = false;
                                                swipeFromRight3 = swipeFromTop;
                                                swipeFromBottom3 = swipeFromBottom;
                                            } else {
                                                swipeFromRight3 = swipeFromTop;
                                                swipeFromBottom3 = swipeFromBottom;
                                            }
                                        } catch (Exception e) {
                                            swipeFromRight = swipeFromRight2;
                                            swipeFromTop2 = swipeFromTop;
                                            swipeFromBottom2 = swipeFromBottom;
                                        }
                                    } else {
                                        swipeFromTop = swipeFromRight3;
                                        swipeFromBottom = swipeFromBottom3;
                                    }
                                    swipeFromRight2 = false;
                                    if (xDistanceArr[i] > 0.0d) {
                                    }
                                    swipeFromLeft = false;
                                    swipeFromRight3 = swipeFromTop;
                                    swipeFromBottom3 = swipeFromBottom;
                                } catch (Exception e2) {
                                    boolean z = swipeFromBottom3;
                                    swipeFromRight = swipeFromRight2;
                                    swipeFromTop2 = swipeFromRight3;
                                    swipeFromBottom2 = z;
                                }
                            } catch (Exception e3) {
                                boolean z2 = swipeFromRight3;
                                swipeFromBottom2 = swipeFromBottom3;
                                swipeFromRight = swipeFromRight2;
                                swipeFromTop2 = z2;
                            }
                        } else {
                            swipeFromLeft = false;
                            swipeFromBottom3 = false;
                            swipeFromRight2 = false;
                            swipeFromRight3 = false;
                        }
                        i++;
                        motionEvent = event;
                        noneLimit = noneLimit2;
                        bottomLimit = bottomLimit2;
                    } catch (Exception e4) {
                        swipeFromTop2 = swipeFromRight3;
                        swipeFromBottom2 = swipeFromBottom3;
                        swipeFromRight = swipeFromRight2;
                    }
                } catch (Exception e5) {
                    swipeFromTop2 = swipeFromRight3;
                    swipeFromBottom2 = swipeFromBottom3;
                    swipeFromRight = swipeFromRight2;
                }
            }
            if (DEBUG) {
                xpLogger.i(TAG, "check xpSGL.getSwipeDirection screenId=" + screenId + " count=" + count + " swipeFromTop=" + swipeFromRight3 + " swipeFromBottom=" + swipeFromBottom3 + " swipeFromRight=" + swipeFromRight2 + " swipeFromLeft=" + swipeFromLeft);
            }
            swipeFromTop2 = swipeFromRight3;
            swipeFromBottom2 = swipeFromBottom3;
            swipeFromRight = swipeFromRight2;
        } else {
            swipeFromLeft = true;
        }
        if (swipeFromTop2) {
            return 1;
        }
        if (swipeFromBottom2) {
            return 2;
        }
        if (swipeFromRight) {
            return 3;
        }
        if (swipeFromLeft) {
            return 4;
        }
        return 0;
    }

    public static int getAngle(Pointer start, Pointer end) {
        if (start != null && end != null) {
            float x = Math.abs(end.x - start.x);
            float y = Math.abs(end.y - start.y);
            double distance = Math.sqrt((x * x) + (y * y));
            return Math.round((float) ((Math.asin(x / distance) / 3.141592653589793d) * 180.0d));
        }
        return 0;
    }

    public static double getDistance(Pointer start, Pointer end) {
        if (start != null && end != null) {
            return Math.sqrt(Math.pow(end.x - start.x, 2.0d) + Math.pow(end.y - start.y, 2.0d));
        }
        return 0.0d;
    }

    public static double getXDistance(Pointer start, Pointer end) {
        if (start != null && end != null) {
            return end.x - start.x;
        }
        return 0.0d;
    }

    public static double getYDistance(Pointer start, Pointer end) {
        if (start != null && end != null) {
            return end.y - start.y;
        }
        return 0.0d;
    }

    public static Pointer getPointer(int index, MotionEvent event) {
        Pointer pointer = new Pointer();
        if (event != null && index >= 0) {
            int count = event.getPointerCount();
            pointer.x = event.getX(index < count ? index : count - 1);
            pointer.y = event.getY(index < count ? index : count - 1);
        }
        return pointer;
    }

    public static Pointer getPointer(int index, float[] x, float[] y) {
        Pointer pointer = new Pointer();
        if (index < x.length && index < y.length) {
            pointer.x = x[index];
            pointer.y = y[index];
        }
        return pointer;
    }

    public static double getDuration(int index, MotionEvent event) {
        if (index >= 0 && event != null && event.getActionMasked() == 2) {
            return (event.getEventTime() - event.getDownTime()) / 1000.0d;
        }
        return 0.0d;
    }

    public double getSwipeSpeed(int index, MotionEvent event) {
        int count = event.getPointerCount();
        if (index >= 0 && index < count && event.getActionMasked() == 2) {
            this.mVelocityTracker.addMovement(event);
            this.mVelocityTracker.computeCurrentVelocity(1000);
            double xVelocity = this.mVelocityTracker.getXVelocity(index);
            double yVelocity = this.mVelocityTracker.getYVelocity(index);
            return Math.sqrt(Math.pow(xVelocity, 2.0d) + Math.pow(yVelocity, 2.0d)) / 1000.0d;
        }
        return 0.0d;
    }

    public static double getMeanSpeed(int index, MotionEvent event, float[] downX, float[] downY, int[] pointerIndex) {
        if (index >= 0 && event != null) {
            double durationtime = getDuration(pointerIndex[index], event);
            double distance = getDistance(getPointer(index, downX, downY), getPointer(pointerIndex[index], event));
            return (distance / durationtime) / 1000.0d;
        }
        return 0.0d;
    }

    public static double centimeterToPixel(double screenHeight, double centimeter) {
        double height = (screenHeight / DisplayMetrics.DENSITY_DEVICE_STABLE) * 2.53d;
        return (centimeter / height) * screenHeight;
    }

    public int getScreenPointCount(float[] downX) {
        int count = 0;
        for (int i = 0; i < downX.length; i++) {
            if (downX[i] > DEF_MULTI_POINTER_MEAN_SPEED_MIN) {
                if (DEBUG) {
                    xpLogger.i(TAG, " getScreenPointCount i=" + i + " downX[i]=" + downX[i]);
                }
                count++;
            }
        }
        if (DEBUG) {
            xpLogger.i(TAG, " getScreenPointCount count=" + count);
        }
        return count;
    }

    /* JADX WARN: Removed duplicated region for block: B:60:0x0392  */
    /* JADX WARN: Removed duplicated region for block: B:61:0x03f7  */
    /* JADX WARN: Removed duplicated region for block: B:84:0x04f1  */
    /* JADX WARN: Removed duplicated region for block: B:87:0x05c3  */
    /* JADX WARN: Removed duplicated region for block: B:88:0x05f3  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void detectXpSystemGesturesEvent(android.view.MotionEvent r45, int r46, com.android.server.wm.SystemGesturesPointerEventListener.Callbacks r47, float[] r48, float[] r49, long[] r50, com.xiaopeng.server.policy.xpSystemGesturesListener.xpSystemGesturesDown r51) {
        /*
            Method dump skipped, instructions count: 1664
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.server.policy.xpSystemGesturesListener.detectXpSystemGesturesEvent(android.view.MotionEvent, int, com.android.server.wm.SystemGesturesPointerEventListener$Callbacks, float[], float[], long[], com.xiaopeng.server.policy.xpSystemGesturesListener$xpSystemGesturesDown):void");
    }

    public static void handleGestureEvent(Context context, int screenId, int direction, int count, boolean isSwipeAreaValid, String extras, WindowManagerPolicy.WindowState win) {
        if (DEBUG) {
            xpLogger.i(TAG, "handleGestureEvent screenId=" + screenId + " direction=" + direction + " count=" + count + " isSwipeAreaValid=" + isSwipeAreaValid + " mxpSystemGesturesMoveList.get(screenId).mMultiBroadcast=" + mxpSystemGesturesMoveList.get(screenId).mMultiBroadcast + " extras=" + extras);
        }
        if (count == 1) {
            if (direction != 1) {
                if (direction != 2) {
                    if (direction == 3) {
                        xpActivityManager.zoomTopActivity(context);
                        return;
                    }
                    return;
                }
                xpActivityManager.stopTopActivity(context);
                return;
            } else if (isSwipeAreaValid && mxpSystemGesturesMoveList.get(screenId).mMultiBroadcast < 1) {
                sendBroadcastForSwipe(context, screenId, direction, count);
                mxpSystemGesturesMoveList.get(screenId).mMultiBroadcast++;
                return;
            } else {
                return;
            }
        }
        if (count != 2) {
            if (count == 3) {
                if (xpPhoneWindowManager.isHighLevelActivity(context, win)) {
                    return;
                }
                if (direction != 1) {
                    if (direction != 2) {
                        if (direction == 3 || direction == 4) {
                            handleMultiSwipeHorizontal(context, screenId, direction, extras);
                            return;
                        }
                        return;
                    }
                    xpActivityManager.zoomTopActivity(context);
                    return;
                } else if (isSwipeAreaValid) {
                    if (mxpSystemGesturesMoveList.get(screenId).mMultiBroadcast < 1) {
                        sendBroadcastForSwipe(context, screenId, direction, count);
                        mxpSystemGesturesMoveList.get(screenId).mMultiBroadcast++;
                        return;
                    }
                    return;
                } else {
                    handleMultiSwipeFromTop(context, screenId, extras);
                    return;
                }
            } else if (count != 4 && count != 5) {
                return;
            }
        }
        if (xpPhoneWindowManager.isHighLevelActivity(context, win)) {
            return;
        }
        if (direction != 1) {
            if (direction != 2) {
                if (direction == 3 || direction == 4) {
                    handleMultiSwipeHorizontal(context, screenId, direction, extras);
                }
            }
        } else if (isSwipeAreaValid && mxpSystemGesturesMoveList.get(screenId).mMultiBroadcast < 1) {
            sendBroadcastForSwipe(context, screenId, direction, count);
            mxpSystemGesturesMoveList.get(screenId).mMultiBroadcast++;
        }
    }

    private static void handleMultiSwipeFromTop(Context context, int screenId, String extras) {
        try {
            IWindowManager wm = xpWindowManager.getWindowManager();
            wm.handleSwipeEvent(1, extras);
            BiDataManager.sendStatData(10001);
        } catch (Exception e) {
        }
    }

    private static void handleMultiSwipeHorizontal(Context context, int screenId, int direction, String extras) {
        if (SharedDisplayManager.enable()) {
            try {
                IWindowManager wm = xpWindowManager.getWindowManager();
                if (direction == 3 || direction == 4) {
                    wm.handleSwipeEvent(direction, extras);
                }
            } catch (Exception e) {
            }
        }
    }

    private static void sendBroadcastForSwipe(Context context, int screenId, int direction, int count) {
        Intent intent = new Intent();
        intent.setAction("android.intent.action.XP_GESTURE_SWIPE");
        Bundle bundle = new Bundle();
        bundle.putString("screenId", Integer.toString(screenId));
        bundle.putString("direction", Integer.toString(direction));
        bundle.putString(AssistDataRequester.KEY_RECEIVER_EXTRA_COUNT, Integer.toString(count));
        intent.putExtra("data", bundle);
        if (DEBUG) {
            xpLogger.i(TAG, " sendBroadcastForSwipe when screenId=" + screenId + " direction=" + direction + " count=" + count);
        }
        context.sendBroadcast(intent, "android.permission.RECV_XP_GESTURE_SWIPE");
    }

    private static void dispatchMotionEvent(final Context context, MotionEvent event, final int screenId) {
        if (event == null || context == null) {
            return;
        }
        MotionEvent motionEvent = MotionEvent.obtain(event);
        int action = motionEvent.getAction();
        int pointerAction = action & 255;
        boolean dispatchToListener = false;
        boolean dispatchToProvider = false;
        if (action != 0) {
            if (action == 1 || action == 2 || action == 3) {
                dispatchToListener = true;
            }
        } else {
            dispatchToListener = true;
            dispatchToProvider = true;
        }
        dispatchToListener = (pointerAction == 5 || pointerAction == 6) ? true : true;
        final boolean _dispatchToListener = dispatchToListener;
        final boolean _dispatchToProvider = dispatchToProvider;
        xpSysGesHandler = new Handler(xpSysHandlerTh.getLooper()) { // from class: com.xiaopeng.server.policy.xpSystemGesturesListener.2
            @Override // android.os.Handler
            public void handleMessage(Message msg) {
                MotionEvent m;
                if (msg.what == 1 && (m = (MotionEvent) msg.obj) != null) {
                    try {
                        if (_dispatchToProvider) {
                            ContentResolver resolver = context.getContentResolver();
                            Settings.Secure.putString(resolver, "key_system_gesture_event", xpSystemGesturesListener.createMotionEventText(m));
                        }
                        if (_dispatchToListener) {
                            String extra = "screenId=" + Integer.toBinaryString(screenId);
                            if (xpSystemGesturesListener.DEBUG) {
                                xpLogger.i(xpSystemGesturesListener.TAG, "xpSGL.dispatchMotionEvent dispatch to lister extra=" + extra + " m=" + m);
                            }
                            xpInputManagerService.get(context).dispatchInputEventToListener(m, extra);
                        }
                    } catch (Exception e) {
                    }
                }
            }
        };
        xpSysGesHandler.obtainMessage(1, motionEvent).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String createMotionEventText(MotionEvent event) {
        if (event == null) {
            return "";
        }
        try {
            JSONObject object = new JSONObject();
            long downTime = event.getDownTime();
            long eventTime = event.getEventTime();
            int action = event.getAction();
            float x = event.getX();
            float y = event.getY();
            int metaState = event.getMetaState();
            object.put("downTime", downTime);
            object.put("eventTime", eventTime);
            object.put("action", action);
            object.put("x", x);
            object.put("y", y);
            object.put("metaState", metaState);
            object.put("screenId", SharedDisplayManager.findScreenId(event));
            return object.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void printMotionEvent(MotionEvent event) {
        if (event == null) {
            return;
        }
        float rawX = event.getRawX();
        float rawY = event.getRawY();
        boolean z = true;
        if (event.getAction() != 0 && event.getAction() != 1) {
            z = false;
        }
        boolean isDownUpEvent = z;
        if (rawX >= 100.0f || rawY >= 100.0f || !isDownUpEvent) {
            return;
        }
        Log.i(TAG, "printMotionEvent " + event.toString());
    }

    private static String createMotionEventText(MotionEvent event, int screenId, int count) {
        if (event == null) {
            return "";
        }
        try {
            JSONObject object = new JSONObject();
            long downTime = event.getDownTime();
            long eventTime = event.getEventTime();
            int action = event.getAction();
            float x = event.getX();
            float y = event.getY();
            int metaState = event.getMetaState();
            object.put("downTime", downTime);
            object.put("eventTime", eventTime);
            object.put("action", action);
            object.put("x", x);
            object.put("y", y);
            object.put(AssistDataRequester.KEY_RECEIVER_EXTRA_COUNT, count);
            object.put("metaState", metaState);
            object.put("screenId", screenId);
            return object.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static WindowManager getWindowManager(Context context) {
        if (context != null) {
            return (WindowManager) context.getSystemService("window");
        }
        return null;
    }

    private void setPerParam(int mDisplayId) {
        if (mDisplayId == 0) {
            SystemProperties.set("persist.sys.doquickpri", String.valueOf(mDisplayId));
        } else if (mDisplayId == 1) {
            SystemProperties.set("persist.sys.doquicksec", String.valueOf(mDisplayId));
        }
    }
}
